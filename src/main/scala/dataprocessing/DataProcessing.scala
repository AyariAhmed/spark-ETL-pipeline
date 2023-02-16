package dataprocessing

import org.apache.spark.sql.functions.{col, count, expr, lit, when, round}
import org.apache.spark.sql.{SaveMode, SparkSession}
import org.apache.spark.sql.types.{DoubleType, IntegerType, StringType, StructField, StructType}
import com.datastax.spark.connector.cql.CassandraConnector

object DataProcessing extends App {

  private val CASSANDRA_HOST = "127.0.0.1"
  private val CASSANDRA_PORT = 9042

  private val spark = SparkSession.builder()
    .appName("DataProcessing")
    .config("spark.master", "local")
    .config("spark.cassandra.connection.host", CASSANDRA_HOST)
    .config("spark.cassandra.connection.port", CASSANDRA_PORT)
    .getOrCreate()

  private val transactionSchema = StructType(
    Array(
      StructField("customer_id", StringType, nullable = false),
      StructField("purch_week", IntegerType, nullable = false),
      StructField("prod_purch", IntegerType, nullable = false),
      StructField("promo_cat", StringType),
      StructField("promo_discount", DoubleType),
      StructField("store_id", IntegerType, nullable = false)
    )
  )

  private val tLogFiles = (1 to 1500).map(index => s"src/main/resources/data/t-logs/datagen_$index.csv")
  private val tLogsDF = spark.read
    .schema(transactionSchema)
    .option("nullValue", "nan")
    .option("mode", "failFast")
    .csv(tLogFiles: _*)

  private def readCsvDF(filename: String) = spark.read
    .option("inferSchema", "true")
    .option("header", "true")
    .csv(s"src/main/resources/data/$filename.csv")

  private val calendarDF = readCsvDF("calendar")
  private val customersDF = readCsvDF("customers")
  private val productsDF = readCsvDF("products")
  private val storesDF = readCsvDF("stores")

  private val startingYear = 2018
  private val weeksPerYear = 52
  private val calendarDFWeekIndex = 1
  private val yearWeekConversion = (col("week_of_year") - calendarDFWeekIndex) + ((col("calendar_year") - startingYear) * weeksPerYear)
  private val calendarPreparedDF = calendarDF.drop("calendar_day", "calendar_week", "day_of_week")
    .dropDuplicates("week_of_year", "calendar_year")
    .withColumn("week", yearWeekConversion)
  private val productsPreparedDF = productsDF.select("prod_id", "subclass_labels", "subclass_coefficients", "prod_base_price", "pareto_weights", "margin")
  private val storePreparedDF = storesDF.drop("store_label")
  private val customersPreparedDF = customersDF.drop("start_date", "end_date")


  private val tLogsPreparedDF = tLogsDF
    .join(customersPreparedDF, "customer_id")
    .join(storePreparedDF, "store_id")
    .join(productsPreparedDF, productsPreparedDF.col("prod_id") === tLogsDF.col("prod_purch")).drop(tLogsDF.col("prod_purch"))
    .join(calendarPreparedDF, calendarPreparedDF.col("week") === tLogsDF.col("purch_week")).drop("week")

  private val weeklySalesDF = tLogsPreparedDF
    .groupBy("purch_week", "prod_id", "store_id")
    .agg(count("*").as("total_weekly_sales"))
    .orderBy("purch_week")

  private val tLogsWithWeeklySales = tLogsPreparedDF.join(weeklySalesDF, Seq("purch_week", "prod_id", "store_id"))

  private val baselineDF = tLogsWithWeeklySales
    .where(col("promo_discount").isNull)
    .groupBy("prod_id", "store_id")
    .agg(expr("percentile_approx(total_weekly_sales, 0.5)").alias("baseline_sales"))

  private val tLogsWithBaselineDF = tLogsWithWeeklySales.join(baselineDF, Seq("prod_id", "store_id"))

  private val tLogsWithPromoLiftDF = tLogsWithBaselineDF
    .withColumn("promo_lift", when(col("promo_discount").isNotNull,
      round(col("total_weekly_sales") / col("baseline_sales"), 5) )
      .otherwise(lit(1)))
    .withColumn("incremental_lift", when(col("promo_discount").isNotNull and (col("total_weekly_sales") > col("baseline_sales")),
      col("total_weekly_sales") - col("baseline_sales")).otherwise(null))


  private val tLogsWithPromoCatTotalSalesDF = tLogsPreparedDF
    .filter(col("promo_cat").isNotNull)
    .groupBy("promo_cat", "prod_id", "store_id")
    .agg(count("*").as("total_sales_promo_cat"))
    .withColumnRenamed("promo_cat", "promo")
    .withColumnRenamed("prod_id", "prod")
    .withColumnRenamed("store_id", "store")

  private val promoCatJoinCondition = tLogsWithPromoCatTotalSalesDF.col("promo") === tLogsWithPromoLiftDF.col("promo_cat") and
    tLogsWithPromoCatTotalSalesDF.col("prod") === tLogsWithPromoLiftDF.col("prod_id") and
    tLogsWithPromoCatTotalSalesDF.col("store") === tLogsWithPromoLiftDF.col("store_id")


  private val salesDataDF = tLogsWithPromoLiftDF
    .join(tLogsWithPromoCatTotalSalesDF, promoCatJoinCondition, "left_outer")
    .drop("promo", "store", "prod")
    .na.fill("none", Seq("promo_cat"))

  private val connector = CassandraConnector(spark.sparkContext.getConf)
  connector.withSessionDo { session =>
    session.execute("CREATE KEYSPACE IF NOT EXISTS challenge WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 }")
    session.execute("CREATE TABLE IF NOT EXISTS challenge.sales_data (prod_id int,  store_id int,  purch_week int, customer_id text, promo_cat text,  promo_discount double, store_pref int, price_sens int,  purch_freq int,  promo_sens int, basket_size int, repurchase_frequency int, store_address text,  store_size int, store_region int, country text,  subclass_labels text, subclass_coefficients int, prod_base_price double, pareto_weights double, margin double, calendar_month int, calendar_year int, week_of_year int, week_of_month int, total_weekly_sales bigint, baseline_sales bigint, promo_lift double,  incremental_lift bigint, total_sales_promo_cat bigint, PRIMARY KEY ((promo_cat, prod_id), store_id, purch_week))")
  }
  // Primary key: Given that we will be querying promo_cat and prod_id, rows with the same promo_cat and prod_id will be stored together on the same Cassandra node, which can improve query performance. The other columns in the primary key (store_id and purch_week) can be used as clustering columns to control the sort order of the data within each partition.

  salesDataDF.write
    .format("org.apache.spark.sql.cassandra")
    .options(Map("table" -> "sales_data", "keyspace" -> "challenge", "confirm.truncate" -> "true"))
    .mode(SaveMode.Overwrite)
    .save()
}

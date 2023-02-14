package dataprocessing

import org.apache.spark.sql.functions.{col, count, sum}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.types.{DoubleType, IntegerType, StringType, StructField, StructType}

object DataProcessing extends App {

  private val spark = SparkSession.builder()
    .appName("DataProcessing")
    .config("spark.master", "local")
    .getOrCreate()

  private val transactionSchema = StructType(
    Array(
      StructField("customer_id", StringType, nullable = false),
      StructField("purch_week", IntegerType, nullable = false),
      StructField("prod_purch", IntegerType, nullable = false),
      StructField("promo_cat", IntegerType),
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

  private val yearWeekConversion = col("week_of_year") +  ((col("calendar_year") - 2018)  * 52)
  private val calendarPreparedDF = calendarDF.drop("calendar_day", "calendar_week", "day_of_week").withColumn("week", yearWeekConversion)
  private val productsPreparedDF = productsDF.selectExpr("prod_id", "subclass_labels", "subclass_coefficients", "prod_base_price", "pareto_weights", "margin")
  private val storePreparedDF = storesDF.drop("store_label")
  private val customersPreparedDF = customersDF.drop("start_date", "end_date")

  calendarPreparedDF.printSchema()
  calendarPreparedDF.orderBy(col("calendar_year").desc).show()

   private val tLogspreparedDF = tLogsDF
     .join(customersPreparedDF, "customer_id")
     .join(storePreparedDF, "store_id")
     .join(productsPreparedDF, productsPreparedDF.col("prod_id") === tLogsDF.col("prod_purch")).drop(tLogsDF.col("prod_purch"))
     .join(calendarPreparedDF, calendarPreparedDF.col("week") === tLogsDF.col("purch_week")).drop("week")

   tLogspreparedDF.printSchema()
   tLogspreparedDF.orderBy(col("calendar_year").desc, col("week_of_year").asc).show()

   val weeklySales = tLogspreparedDF.groupBy("purch_week", "prod_id").agg(count("prod_id").as("total_sales")).orderBy("week_year")
   weeklySales.show()
   weeklySales.selectExpr("sum(total_sales)").show()
   tLogspreparedDF.select(count("*")).show()
   tLogsDF.select(count("*")).show()
   println(s"tlogs : ${tLogsDF.count()} tlogsPrepared ${tLogspreparedDF.count()} ")
}

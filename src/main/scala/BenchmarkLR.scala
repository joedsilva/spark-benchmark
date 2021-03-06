import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.Row
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.linalg.Vector
import org.apache.spark.mllib.linalg.Vectors
import org.apache.spark.mllib.linalg.distributed.IndexedRow
import org.apache.spark.mllib.linalg.distributed.IndexedRowMatrix
import org.apache.spark.mllib.linalg.distributed.BlockMatrix

import org.apache.logging.log4j.scala.Logging

import net.sf.geographiclib.Geodesic

object BenchmarkLR extends Logging {

  def run(spark: SparkSession, datasetLoader: DatasetLoader) {

    import spark.implicits._

    val time = new TimeProfiler("lr")
    time.start()

    val tripdata2017 = datasetLoader.load("tripdata2017")
    tripdata2017.cache()
    tripdata2017.foreach(Unit => ())
    time.tick(1)
    tripdata2017.foreach(Unit => ())
    time.tick(-1)
    tripdata2017.show(5)
    tripdata2017.describe().show()

    time.tick(0)
    val stations2017 = datasetLoader.load("stations2017")
    stations2017.cache()
    stations2017.foreach(Unit => ())
    time.tick(1)
    stations2017.foreach(Unit => ())
    time.tick(-1)
    stations2017.show(5)
    stations2017.describe().show()

    val freqStations = tripdata2017.filter(col("stscode") =!= col("endscode"))
      .groupBy("stscode", "endscode").agg(count("id").alias("numtrips"))
      .filter(col("numtrips") >= 50)
    freqStations.show(5)
    freqStations.describe().show()

    val freqStationsCord = freqStations.join(stations2017, col("stscode") === col("scode"))
      .withColumnRenamed("slatitude", "stlat").withColumnRenamed("slongitude", "stlong")
      .drop("sispublic").drop("scode").drop("sname")
      .join(stations2017, col("endscode") === col("scode"))
      .withColumnRenamed("slatitude", "enlat").withColumnRenamed("slongitude", "enlong")
      .drop("sispublic").drop("scode").drop("sname")
    freqStationsCord.show(5)

    val geoDistance = (lat1: Double, lon1: Double, lat2: Double, lon2: Double)
      => Geodesic.WGS84.Inverse(lat1, lon1, lat2, lon2).s12
    val geoDistanceUDF = udf(geoDistance)
    val freqStationsDist = freqStationsCord.withColumn("vdistm",
        round(geoDistanceUDF(col("stlat"), col("stlong"), col("enlat"), col("enlong"))))
    freqStationsDist.show(5)

    val tripData = tripdata2017.join(freqStationsDist, usingColumns=Seq("stscode", "endscode"))
      .select("id", "duration", "vdistm")
    tripData.show(5)
    tripData.describe().show()

    val uniqueTripDist = tripData.select("vdistm").distinct.sort(asc("vdistm"))
    uniqueTripDist.show(5)
    uniqueTripDist.describe().show()

    val splitTripDist = uniqueTripDist.randomSplit(Array(1, 2), 42)
    val testTripDist = splitTripDist(0)
    testTripDist.show(5)
    testTripDist.describe().show()

    val trainTripDist = splitTripDist(1)
    trainTripDist.show(5)
    trainTripDist.describe().show()

    var trainData = tripData.select("vdistm", "duration")
      .join(trainTripDist, usingColumns=Seq("vdistm"))
    trainData.show(5)
    trainData.describe().show()

    val maxdist = uniqueTripDist.agg(max(col("vdistm"))).head().getDouble(0)
    println(maxdist)
    val maxduration = tripData.agg(max(col("duration"))).head().getInt(0)
    println(maxduration)

    trainData = trainData.select(col("vdistm") / maxdist as "vdistm", col("duration") / maxduration as "duration")
    trainData.show(5)
    trainData.describe().show()

    val trainDataSet = trainData.select("vdistm").withColumn("x0", lit(1)).select("x0", "vdistm")
    trainDataSet.show(5)
    val trainDataSetDuration = trainData.select("duration")
    trainDataSetDuration.show(5)
    var params = Seq(1.0).toDF("a").withColumn("b", lit(1.0))
    params.show(1)

    def dataframeToMatrix(df: Dataset[Row]) : BlockMatrix = {
      val assembler = new VectorAssembler().setInputCols(df.columns).setOutputCol("vector")
      val df2 = assembler.transform(df)
      return new IndexedRowMatrix(df2.select("vector").rdd.map{
        case Row(v: Vector) => Vectors.fromML(v)
      }.zipWithIndex.map { case (v, i) => IndexedRow(i, v) }).toBlockMatrix()
    }

    time.tick(0)
    val trainDataSetMat = dataframeToMatrix(trainDataSet)
    trainDataSetMat.cache()
    Utils.eval(spark, trainDataSetMat)
    time.tick(1)
    Utils.eval(spark, trainDataSetMat)
    time.tick(-1)
    var paramsMat = dataframeToMatrix(params)
    var pred = trainDataSetMat.multiply(paramsMat.transpose)

    def squaredErr(actual: BlockMatrix, predicted: BlockMatrix) : Double = {
      var s: Double = 0
      val it = actual.subtract(predicted).toLocalMatrix().rowIter
      while (it.hasNext) {
        s += scala.math.pow(it.next.apply(0), 2)
      }
      return s / (2 * actual.numRows())
    }

    time.tick(0)
    val trainDataSetDurationMat = dataframeToMatrix(trainDataSetDuration)
    trainDataSetDurationMat.cache()
    Utils.eval(spark, trainDataSetDurationMat)
    time.tick(1)
    Utils.eval(spark, trainDataSetDurationMat)
    time.tick(-1)
    var sqerr = squaredErr(trainDataSetDurationMat, pred)
    println(sqerr)

    def gradDesc(actual: BlockMatrix, predicted: BlockMatrix,
                 indata: BlockMatrix) : Seq[Double] = {
      val m = predicted.subtract(actual).transpose.multiply(indata).toLocalMatrix()
      val n = actual.numRows()
      return Seq(m.apply(0, 0) / n, m.apply(0, 1) / n)
    }

    val alpha = 0.1

    val update = gradDesc(trainDataSetDurationMat, pred, trainDataSetMat)
    params = params.select(col("a") - alpha * update(0) as "a",
      col("b") - alpha * update(1) as "b")
    params.show(1)

    paramsMat = dataframeToMatrix(params)
    pred = trainDataSetMat.multiply(paramsMat.transpose)
    sqerr = squaredErr(trainDataSetDurationMat, pred)
    println(sqerr)

    time.tick(0)
    val gmdata2017 = datasetLoader.load("gmdata2017")
    gmdata2017.cache()
    gmdata2017.foreach(Unit => ())
    time.tick(1)
    gmdata2017.foreach(Unit => ())
    time.tick(-1)
    gmdata2017.show(5)
    gmdata2017.describe().show()

    val gtripData = gmdata2017.join(tripdata2017, usingColumns=Seq("stscode", "endscode"))
      .join(freqStationsCord, usingColumns=Seq("stscode", "endscode"))
      .select("id", "duration", "gdistm", "gduration")
      .withColumn("gdistm", col("gdistm").cast("double"))
    gtripData.show(5)
    gtripData.describe().show()

    val guniqueTripDist = gtripData.select("gdistm").distinct.sort(asc("gdistm"))
    val gsplitTripDist = guniqueTripDist.randomSplit(Array(1, 2), 42)
    val gtestTripDist = gsplitTripDist(0)
    val gtrainTripDist = gsplitTripDist(1)
    var gtrainData = gtripData.select("gdistm", "duration")
      .join(gtrainTripDist, usingColumns=Seq("gdistm"))

    val gmaxdist = guniqueTripDist.agg(max(col("gdistm"))).head().getDouble(0)
    println(gmaxdist)
    val gmaxduration = gtripData.agg(max(col("duration"))).head().getInt(0)
    println(gmaxduration)
    gtrainData = gtrainData.select(col("gdistm") / gmaxdist as "gdistm", col("duration") / gmaxduration as "duration")

    val gtrainDataSet = gtrainData.select("gdistm").withColumn("x0", lit(1)).select("x0", "gdistm")
    val gtrainDataSetDuration = gtrainData.select("duration")
    var gparams = Seq(1.0).toDF("a").withColumn("b", lit(1.0))

    time.tick(0)
    val gtrainDataSetMat = dataframeToMatrix(gtrainDataSet)
    gtrainDataSetMat.cache()
    Utils.eval(spark, gtrainDataSetMat)
    time.tick(1)
    Utils.eval(spark, gtrainDataSetMat)
    time.tick(-1)
    var gparamsMat = dataframeToMatrix(gparams)
    var gpred = gtrainDataSetMat.multiply(gparamsMat.transpose)
    time.tick(0)
    val gtrainDataSetDurationMat = dataframeToMatrix(gtrainDataSetDuration)
    gtrainDataSetDurationMat.cache()
    Utils.eval(spark, gtrainDataSetDurationMat)
    time.tick(1)
    Utils.eval(spark, gtrainDataSetDurationMat)
    time.tick(-1)
    var gsqerr = squaredErr(gtrainDataSetDurationMat, gpred)
    println(gsqerr)
    val gupdate = gradDesc(gtrainDataSetDurationMat, gpred, gtrainDataSetMat)
    gparams = gparams.select(col("a") - alpha * gupdate(0) as "a",
      col("b") - alpha * gupdate(1) as "b")
    gparamsMat = dataframeToMatrix(gparams)
    gpred = gtrainDataSetMat.multiply(gparamsMat.transpose)
    gsqerr = squaredErr(gtrainDataSetDurationMat, gpred)
    println(gsqerr)

    time.tick(0)
    for (i <- 0 to 999) {
      val gparamsMat = dataframeToMatrix(gparams)
      gpred = gtrainDataSetMat.multiply(gparamsMat.transpose)
      val gupdate = gradDesc(gtrainDataSetDurationMat, gpred, gtrainDataSetMat)
      gparams = gparams.select(col("a") - alpha * gupdate(0) as "a",
        col("b") - alpha * gupdate(1) as "b")
      if ((i+1)%100 == 0) {
        println(s"Error rate after ${i+1} iterations is ${squaredErr(gtrainDataSetDurationMat, gpred)}")
      }
    }

    gparams.show(1)
    gsqerr = squaredErr(gtrainDataSetDurationMat, gpred)
    println(gsqerr)
    time.tick(2)

    var gtestData = gtripData.select("gdistm", "duration")
      .join(gtestTripDist, usingColumns=Seq("gdistm"))
    gtestData = gtestData.select(col("gdistm") / gmaxdist as "gdistm", col("duration") / gmaxduration as "duration")
    val gtestDataSet = gtestData.select("gdistm").withColumn("x0", lit(1)).select("x0", "gdistm")
    val gtestDataSetDuration = gtestData.select("duration")

    time.tick(3)
    val gtestDataSetDurationMat = dataframeToMatrix(gtestDataSetDuration)
    gtestDataSetDurationMat.cache()
    Utils.eval(spark, gtestDataSetDurationMat)
    time.tick(1)
    Utils.eval(spark, gtestDataSetDurationMat)
    time.tick(-1)
    val gtestDataSetMat = dataframeToMatrix(gtestDataSet)
    gtestDataSetMat.cache()
    Utils.eval(spark, gtestDataSetMat)
    time.tick(1)
    Utils.eval(spark, gtestDataSetMat)
    time.tick(-1)
    gparamsMat = dataframeToMatrix(gparams)
    val gtestpred = gtestDataSetMat.multiply(gparamsMat.transpose)

    val gdurationMat = dataframeToMatrix(Seq(gmaxduration).toDF("duration"))
    val gtestsqerr1 = squaredErr(gtestDataSetDurationMat.multiply(gdurationMat), gtestpred.multiply(gdurationMat))
    println(gtestsqerr1)

    val gdurationDataMat = dataframeToMatrix(gtripData.join(gtestTripDist, usingColumns=Seq("gdistm")).select("gduration"))
    val gtestsqerr2 = squaredErr(gtestDataSetDurationMat.multiply(gdurationMat), gdurationDataMat)
    println(gtestsqerr2)
    time.tick(3)

    time.log()
  }
}

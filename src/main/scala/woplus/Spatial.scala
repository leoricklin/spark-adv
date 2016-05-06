package woplus

import org.apache.spark.mllib.clustering.{KMeansModel, KMeans}
import org.apache.spark.mllib.linalg.{Vector, Vectors}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row,SQLContext}
import org.apache.spark.{SparkContext, SparkConf}
import scala.collection.immutable.IndexedSeq
import scala.collection.{immutable, Map}
import scala.collection.mutable.ArrayBuffer

/**
 * Created by leorick on 2016/4/28.
 */
object Spatial {
  val appName = "woplus.spatial"

  case class spatialDetail(imei:String, date:String, hour:Int, x:Double, y:Double,
                           areaid:Int, clusterid:Int)

  def spatialDetail2Str(record:spatialDetail): String = {
    Array(record.imei, record.date, record.hour.toString,record.x.toString,record.y.toString,record.areaid.toString,record.clusterid.toString).
      mkString(",")
  }

  def str2spatialDetail(line:String): spatialDetail = {
    val toks = line.split(""",""")
    spatialDetail(toks(0), toks(1), toks(2).toInt, toks(3).toDouble, toks(4).toDouble,
      toks(5).toInt, toks(6).toInt)
  }

  def row2spatialDetail(row:Row): spatialDetail = {
    spatialDetail(row.getString(0),row.getString(1),row.getInt(2),row.getDouble(3),row.getDouble(4),
      row.getInt(5),row.getInt(6))
  }

  def loadSpatialSrc(sc:SparkContext, path:String): RDD[String] = {
    sc.textFile(path)
  }

  def splitSpatial(srcSpatial:RDD[String]): RDD[ArrayBuffer[String]] = {
    // 逐一解析欄位
    srcSpatial.mapPartitions{ ite =>
      ite.map { line =>
        val ary = new ArrayBuffer[String]()
        var start = 0
        var end = 0
        var idx = 0
        var tok = ""
        while (start <= line.size) {
          idx = line.indexOf(",", start)
          end = if( idx < 0 || idx > line.size ) line.size else idx
          tok = line.substring(start, end)
          ary += (tok.size match {
            case 0 => "0"
            case _ => tok })
          start = end + 1
        }
        ary } }
  }

  /**
   *
   * @param spatial
   *   e.g.: 20160105,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,0,0,0,0,0,0,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001
   * @return RDD[(imei:String, date:String, hour:Int, gpsXY:Vector)]
   *   e.g.: 4b6b6fc4af31748d3b9f13781fe86503,20160103,20,[120.9150694,31.1202111]
   */
  def tok2Detail(spatial:RDD[ArrayBuffer[String]]): RDD[(String, String, Int, Vector)] = {
    spatial.flatMap{ ary =>
      val date = ary(0)
      val imei = ary(1)
      (2 to 48 by 2).
        map{ idx => (imei, date, (idx/2-1), Vectors.dense(ary(idx).toDouble, ary(idx+1).toDouble)) }.
        filter{ case (imei, date, hour, vec) => vec(0) != 0 && vec(1) != 0 } }
  }

  def fitKmean(vectors:RDD[Vector], numClusters:Int, iterations:Int = 10, runs:Int = 3, mode:String = "k-means||", seed:Long = 1L): KMeansModel = {
    KMeans.train(vectors, numClusters, iterations, runs, mode, seed)
  }

  def saveKModel(sc:SparkContext, kmodel:KMeansModel, path:String) = {
    // sc.parallelize(kmodel.clusterCenters).saveAsTextFile(path)
    import java.io._
    val file = new File(path)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write(kmodel.clusterCenters.map{ vec => f"${vec(0)},${vec(1)}" }.mkString("\n"))
    bw.close()
  }

  def loadKModel(sc:SparkContext, path:String): KMeansModel = {
    import scala.io.Source
    val vecs: Array[Vector] = Source.
      fromFile(path).
      getLines.toArray.
      map{ line => line.split(""",""") }.
      map{ toks => Vectors.dense( toks.map{_.toDouble} )}
    new KMeansModel( vecs )
  }

  def saveSpatialDetail(sc:SparkContext, details: RDD[spatialDetail], path:String) = {
    import java.io._
    val file = new File(path)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write( details.
      map{ spatialDetail => spatialDetail2Str(spatialDetail) }.
      collect().
      mkString("\n") )
    bw.close()
  }

  def loadSpatialDetail(sc:SparkContext, path:String): RDD[spatialDetail] = {
    // sc.textFile(path).map{ line => str2spatialDetail(line) }
    import scala.io.Source
    sc.parallelize(Source.fromFile(path).getLines.toArray.map{ line => str2spatialDetail(line)})
  }

  def main(args: Array[String]) {
    val sparkConf = new SparkConf().setAppName(appName)
    val sc = new SparkContext(sparkConf)
    val sqlContext = new SQLContext(sc)
    import sqlContext.implicits._
    // 讀取完整資料
    var path = "file:///home/leoricklin/dataset/woplus/spatial/"
    // "file:///w.data/WORKSPACE.2/dataset/woplus/spatial"
    val srcSpatial = loadSpatialSrc(sc, path)
    /*
    srcSpatial.take(5).foreach(println)
20160105,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,,,,,,,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001
20160106,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,,,121.2839,31.34085001,,,,,,,,,,,,,,,,,,
20160104,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,,,,,,,121.2839,31.34085001,,,121.2839,31.34085001,,,,,,,121.2839,31.34085001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,,
20160103,ff7cfb0e717cc3a48af443209168ef92,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.29018,31.32896001,,,121.29018,31.32896001,,,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.41694,31.24027001,121.29018,31.32896001,,,121.2839,31.34085001,121.2839,31.34085001
20151229,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29852,31.35621001,121.2839,31.34085001,121.2839,31.34085001,121.29852,31.35621001,121.29852,31.35621001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,,,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001
     */
    // 使用split()時若中間出現空值則會出現欄位數量不固定
    /*
    val srcSpatial = sc.textFile("file:///media/sf_WORKSPACE.2W/dataset/woplus/spatial/spatial.csv",4).
      map{_.split(""",\s*""")}
    srcSpatial.first
    // : String = 20160105,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,,,,,,,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001
    NAStat.statsWithMissing( srcSpatial.map{ toks => Array(toks.size.toDouble)} )
    // split by ','        = Array(stats: (count: 5776605, mean: 42.687266, stdev: 9.450486, max: 50.000000, min: 4.000000), NaN: 0)
    // split by """,\s*""" = Array(stats: (count: 5776605, mean: 42.687266, stdev: 9.450486, max: 50.000000, min: 4.000000), NaN: 0)
    val err = srcSpatial.filter( toks => toks.size == 4 )
    err.count // 14532
    err.take(5)
Array(20151227, 311f37c8a612d438fc8b7060facdcb3e, 121.49486, 31.04846),
Array(20151231, 52f84c03c2ca02c0473b2cae5c2fa79e, 121.3377, 31.28607),
Array(20160103, 1c9ac11dae7cf737d78af8ce3041ae82, 121.49333, 31.09325001),
Array(20160102, 96ac54326a98b35adc3fd8e5fdc2edce, 121.423, 31.13925001),
Array(20160101, 643ce169d1f041f9312d27bc7a61fb61, 121.47533, 31.25294001)
     NAStat.statsWithMissing(
       srcSpatial.filter( toks => toks.size == 50 ).map{ toks => Array(toks.size.toDouble)} )
     // Array(stats: (count: 2437419, mean: 50.000000, stdev: 0.000000, max: 50.000000, min: 50.000000), NaN: 0)
     */
    // 分隔GPS紀錄
    val spatialToks = splitSpatial(srcSpatial)
    /*
    NAStat.statsWithMissing( spatial.map(ary => Array(ary.size.toDouble)) )
Array(stats: (count: 5,776,605, mean: 50.000000, stdev: 0.000000, max: 50.000000, min: 50.000000), NaN: 0)
24,019,056

    spatial.take(5).foreach(ary => println(ary.mkString(",")))
20160105,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,0,0,0,0,0,0,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001
20160106,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,0,0,121.2839,31.34085001,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
20160104,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,0,0,0,0,0,0,121.2839,31.34085001,0,0,121.2839,31.34085001,0,0,0,0,0,0,121.2839,31.34085001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,0,0
20160103,ff7cfb0e717cc3a48af443209168ef92,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.29018,31.32896001,121.2839,31.34085001,121.29018,31.32896001,121.2839,31.34085001,121.29018,31.32896001,0,0,121.29018,31.32896001,0,0,121.29018,31.32896001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.41694,31.24027001,121.29018,31.32896001,0,0,121.2839,31.34085001,121.2839,31.34085001
20151229,ff7cfb0e717cc3a48af443209168ef92,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.29852,31.35621001,121.2839,31.34085001,121.2839,31.34085001,121.29852,31.35621001,121.29852,31.35621001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,0,0,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001,121.2839,31.34085001

     spatial.map(ary => ary(0)).distinct().collect().foreach(println)
20151230 20151231 20151227 20160101 20151228 20151229 20160102 20160103 20160104 20160105 20160106
     */
    // 將GPS紀錄轉成 detail record
    val details = tok2Detail(spatialToks).cache()
    /*
    println(subset.take(5).map{ ary => ary.mkString(",")}.mkString("\n"))
20160103,4b6b6fc4af31748d3b9f13781fe86503,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,120.9150694,31.1202111,120.92551,31.12262,120.92551,31.12262,120.9150694,31.1202111
20160105,6bcb26d6f642c8f06767ff38a78c2e5d,0,0,0,0,0,0,0,0,0,0,0,0,0,0,121.47838,31.32222001,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0
20151229,978bfc87a33594300f9751738d5c21ce,0,0,121.38675,31.10897001,121.38675,31.10897001,121.38675,31.10897001,121.38675,31.10897001,121.38675,31.10897001,121.38675,31.10897001,0,0,121.35935,31.16551001,121.37528,31.18484001,121.37528,31.18484001,121.37873,31.18177001,121.37528,31.18484001,121.37873,31.18177001,121.37873,31.18177001,121.37528,31.18484001,0,0,121.37528,31.18484001,121.3566,31.17225001,121.38675,31.10897001,121.38675,31.10897001,121.38675,31.10897001,0,0,121.38675,31.10897001

    println(details.collect.map{ tup => f"${tup._1},${tup._2},${tup._3},${tup._4}"}.mkString("\n"))
4b6b6fc4af31748d3b9f13781fe86503,20160103,20,[120.9150694,31.1202111]
4b6b6fc4af31748d3b9f13781fe86503,20160103,21,[120.92551,31.12262]
4b6b6fc4af31748d3b9f13781fe86503,20160103,22,[120.92551,31.12262]
4b6b6fc4af31748d3b9f13781fe86503,20160103,23,[120.9150694,31.1202111]
6bcb26d6f642c8f06767ff38a78c2e5d,20160105,7,[121.47838,31.32222001]
978bfc87a33594300f9751738d5c21ce,20151229,1,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,2,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,3,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,4,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,5,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,6,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,8,[121.35935,31.16551001]
978bfc87a33594300f9751738d5c21ce,20151229,9,[121.37528,31.18484001]
978bfc87a33594300f9751738d5c21ce,20151229,10,[121.37528,31.18484001]
978bfc87a33594300f9751738d5c21ce,20151229,11,[121.37873,31.18177001]
978bfc87a33594300f9751738d5c21ce,20151229,12,[121.37528,31.18484001]
978bfc87a33594300f9751738d5c21ce,20151229,13,[121.37873,31.18177001]
978bfc87a33594300f9751738d5c21ce,20151229,14,[121.37873,31.18177001]
978bfc87a33594300f9751738d5c21ce,20151229,15,[121.37528,31.18484001]
978bfc87a33594300f9751738d5c21ce,20151229,17,[121.37528,31.18484001]
978bfc87a33594300f9751738d5c21ce,20151229,18,[121.3566,31.17225001]
978bfc87a33594300f9751738d5c21ce,20151229,19,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,20,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,21,[121.38675,31.10897001]
978bfc87a33594300f9751738d5c21ce,20151229,23,[121.38675,31.10897001]
     */
    // 取出GPS紀錄
    val xy = details.map{ case (imei, date, gour, xy) => xy }.cache()
    /*
    未排除 0 的筆數: vectors.count = 138,638,520
    已排除 0 的筆數: vectors.count = 59,345,298,
    已排除 0 且不重複的筆數: vectors.count = 11,266
    落在四大商圈的筆數:               24,019,056
    大宁地区
    *	bounds = { north: 31.30025, south: 31.2354,  east: 121.48956, west: 121.44149 }
    *	5537069 個座標
    北外滩区域
    *	bounds = { north: 31.28999, south: 31.23981, east: 121.55445, west: 121.49814 }
    *	2957485 個座標
    卢湾地区
    *	bounds = { north: 31.23482, south: 31.18446,  east: 121.49934, west: 121.4245 }
    *	7918600 個座標
    三林地区
    *	bounds = { north: 31.23658, south: 31.19018, east: 121.55977, west: 121.50621 }
    *	3309221 個座標

    vectors.take(5)
= Array([121.1916611,31.1527695], [121.4451389,31.1443195], [121.47039,31.33998001], [121.51515,31.2110111], [121.53047,31.30082])
     */
    // 定義四大區域
    val areas = sc.
      broadcast( Seq(
      0->Seq(121.44149, 121.48956, 31.2354, 31.30025),  // 大宁地区
      1->Seq(121.49814, 121.55445, 31.23981, 31.28999), // 北外滩区域
      2->Seq(121.4245,  121.49934, 31.18446, 31.23482), // 卢湾地区
      3->Seq(121.50621, 121.55977, 31.19018, 31.23658) ).// 三林地区
      toMap ).
      value
    // 四大區域內GPS紀錄
    val xyByAreas: immutable.Map[Int, RDD[Vector]] = areas.
      map{ case (idx, area) => (idx, xy.filter{vec => vec(0) > area(0) && vec(0) < area(1) && vec(1) > area(2) && vec(1) < area(3)}) }
    xyByAreas.foreach{ case (idx, vecs) => vecs.cache() }
    xy.unpersist(true)
    /*
    val area01 = vectors.filter{vec => vec(0) > 121.44149 && vec(0) < 121.48956 && vec(1) > 31.2354 && vec(1) < 31.30025 }.cache
    val area02 = vectors.filter{vec => vec(0) > 121.49814 && vec(0) < 121.55445 && vec(1) > 31.23981 && vec(1) < 31.28999 }.cache
    val area03 = vectors.filter{vec => vec(0) > 121.4245  && vec(0) < 121.49934 && vec(1) > 31.18446 && vec(1) < 31.23482 }.cache
    val area04 = vectors.filter{vec => vec(0) > 121.50621 && vec(0) < 121.55977 && vec(1) > 31.19018 && vec(1) < 31.23658 }.cache
    println(f"[${area01.count}][${area01.sample(false, 0.005).collect().mkString(";")}]")
    println(f"[${area02.count}][${area02.sample(false, 0.005).collect().mkString(";")}]")
    println(f"[${area03.count}][${area03.sample(false, 0.005).collect().mkString(";")}]")
    println(f"[${area04.count}][${area04.sample(false, 0.005).collect().mkString(";")}]")
[945][[121.4798306,31.2391889], [121.46913,31.27028]]
[420][[121.50396,31.25644001];[121.50341,31.27556]]
[1380][[121.45458,31.23205001];[121.45695,31.23172001];[121.44257,31.22348001];[121.4341,31.19079];[121.45101,31.20622001];[121.43584,31.19481001];[121.4667111,31.2249611];[121.46944,31.19001];[121.44972,31.22525];[121.4657,31.22807001]]
[491][[121.55846,31.21318]]
     */
    // 四大區域內 unique GPS紀錄
    val uniqXyByAreas: immutable.Map[Int, RDD[Vector]] = xyByAreas.
      map{ case (idx, vecs) => (idx, vecs.distinct())}
    uniqXyByAreas.foreach{case (idx, rdd) => rdd.cache()}
    /*
    uniqXyInAreas.foreach{ rdd => println(f"[${rdd.count}][${rdd.sample(false, 0.005).collect().mkString(";")}]")}
[945][[121.47522,31.26962001];[121.45301,31.24930001];[121.48297,31.27669];[121.47035,31.2414]]
[420][[121.53739,31.26096];[121.49957,31.28549];[121.54609,31.28196]]
[1380][[121.44857,31.19267];[121.4551611,31.2179695];[121.4852194,31.23445];[121.43625,31.1962195]]
[491][[121.52556,31.20031001]]
     */
    // 四大區域內各自分群
    var kmodels: immutable.Map[Int, KMeansModel] = uniqXyByAreas.
      map{ case (idx, vecs) => (idx, fitKmean(vecs, 10))}
    // 驗證
    kmodels.
      foreach{ case (idx, model) => println("="*50 +"\n"+ model.clusterCenters.mkString("\n"))}
    /*
==================================================
[121.47904218021975,31.27436613439561]
[121.4822382,31.242465427164177]
[121.46295560847456,31.268907813050856] [121.47989070408164,31.292249250714285] [121.45070153015872,31.2451486634127] [121.45020977500002,31.289576749423084] [121.44969039230764,31.26387677423077] [121.46505258314606,31.250673503483142] [121.48237673983056,31.25877582330509] [121.47152513499998,31.238059283699982]
==================================================
[121.54497624999998,31.279527461136365] [121.54562910434782,31.24945867739131] [121.52273102857141,31.243289817346938] [121.50350558823527,31.285694119411765] [121.50478317391305,31.256429499130434] [121.50794794347829,31.274031308695655] [121.51631573500002,31.262953368499993] [121.50350845,31.242291090185176] [121.52527335294117,31.281408139411763] [121.53139298507465,31.264620886567158]
==================================================
[121.48775176810342,31.226870036293093] [121.43753732790697,31.226237231686042] [121.43246179481484,31.192586475629632] [121.45436309874212,31.22898911861635] [121.47196186949999,31.22731812925001] [121.48546462410714,31.203046577053577] [121.44844290000002,31.195363215287358] [121.46792063923073,31.20550328438461] [121.45229112400004,31.213080561359995] [121.43183557291667,31.21165184875]
==================================================
[121.52187648039215,31.231059664313722] [121.51504965714284,31.19749158371429] [121.55043973333335,31.209229098333328] [121.5122770906977,31.230297268604662] [121.52864429887637,31.225297347865173] [121.5136195622222,31.21369013822222] [121.54395201166665,31.226609231833336] [121.54822319090908,31.195514252272734] [121.53635720454548,31.212127111363635] [121.5247843088889,31.208801892]
     */
    // 儲存模型
    kmodels.foreach{ case (idx, model) => saveKModel(sc, model, f"/home/leoricklin/dataset/woplus/spatial.kmean/${idx}%02d")}
    /*
    $ cat /home/leoricklin/dataset/woplus/spatial.kmean/03
121.52187648039215,31.231059664313722
121.51504965714284,31.19749158371429
121.55043973333335,31.209229098333328
121.5122770906977,31.230297268604662
121.52864429887637,31.225297347865173
121.5136195622222,31.21369013822222
121.54395201166665,31.226609231833336
121.54822319090908,31.195514252272734
121.53635720454548,31.212127111363635
121.5247843088889,31.208801892
     */
    // 載入模型
    kmodels = sc.broadcast(
        areas.map{ case (idx, area) => (idx, loadKModel(sc, f"/home/leoricklin/dataset/woplus/spatial.kmean/${idx}%02d")) } ).value
    // 驗證
    /*
    println(newKModel.head._2.clusterCenters.map{ vec => vec.toArray.mkString(";")}.mkString("\n"))
121.47904218021975;31.27436613439561
121.4822382;31.242465427164177
121.46295560847456;31.268907813050856
121.47989070408164;31.292249250714285
121.45070153015872;31.2451486634127
121.45020977500002;31.289576749423084
121.44969039230764;31.26387677423077
121.46505258314606;31.250673503483142
121.48237673983056;31.25877582330509
121.47152513499998;31.238059283699982
     */
    // 計算四區域內10熱點的中心點及GPS紀錄數
    val clusterCentCntByAreas = xyByAreas.
      map{ case (idx, vecs) =>
        val model = kmodels(idx)
        (idx, (model.clusterCenters.zipWithIndex.map(_.swap).toMap, model.predict(vecs).countByValue() )) }
    // 驗證
    clusterCentCntByAreas.foreach{ case (idx, (centers: immutable.Map[Int, Vector], cnts: Map[Int, Long])) =>
      println(f"${idx} ${"="*50}")
      println(centers.
        map{ case (idx, vec) => f"centers=[${vec}], counts=[${cnts(idx)}]" }.
        mkString("\n") ) }
    /*
0 ==================================================
centers=[[121.47904218021975,31.27436613439561]], counts=[608731]
centers=[[121.45020977500002,31.289576749423084]], counts=[313497]
centers=[[121.4822382,31.242465427164177]], counts=[427569] centers=[[121.44969039230764,31.26387677423077]], counts=[670324] centers=[[121.47152513499998,31.238059283699982]], counts=[498257] centers=[[121.46295560847456,31.268907813050856]], counts=[349406] centers=[[121.46505258314606,31.250673503483142]], counts=[614182] centers=[[121.47989070408164,31.292249250714285]], counts=[707533] centers=[[121.48237673983056,31.25877582330509]], counts=[667242] centers=[[121.45070153015872,31.2451486634127]], counts=[680328]
1 ==================================================
centers=[[121.54497624999998,31.279527461136365]], counts=[409942] centers=[[121.50794794347829,31.274031308695655]], counts=[428844] centers=[[121.54562910434782,31.24945867739131]], counts=[127138] centers=[[121.51631573500002,31.262953368499993]], counts=[370364] centers=[[121.53139298507465,31.264620886567158]], counts=[407852] centers=[[121.52273102857141,31.243289817346938]], counts=[323685] centers=[[121.50350845,31.242291090185176]], counts=[218510] centers=[[121.50350558823527,31.285694119411765]], counts=[32722] centers=[[121.52527335294117,31.281408139411763]], counts=[345452] centers=[[121.50478317391305,31.256429499130434]], counts=[292976]
2 ==================================================
centers=[[121.48775176810342,31.226870036293093]], counts=[763932] centers=[[121.48546462410714,31.203046577053577]], counts=[925376] centers=[[121.43753732790697,31.226237231686042]], counts=[791360] centers=[[121.44844290000002,31.195363215287358]], counts=[727933] centers=[[121.43183557291667,31.21165184875]], counts=[640788] centers=[[121.43246179481484,31.192586475629632]], counts=[890344] centers=[[121.46792063923073,31.20550328438461]], counts=[1101100] centers=[[121.45436309874212,31.22898911861635]], counts=[832215] centers=[[121.45229112400004,31.213080561359995]], counts=[500491] centers=[[121.47196186949999,31.22731812925001]], counts=[745061]
3 ==================================================
centers=[[121.52187648039215,31.231059664313722]], counts=[361639] centers=[[121.5136195622222,31.21369013822222]], counts=[428490] centers=[[121.51504965714284,31.19749158371429]], counts=[342004] centers=[[121.54395201166665,31.226609231833336]], counts=[235903] centers=[[121.5247843088889,31.208801892]], counts=[367574] centers=[[121.55043973333335,31.209229098333328]], counts=[205962] centers=[[121.54822319090908,31.195514252272734]], counts=[114235] centers=[[121.5122770906977,31.230297268604662]], counts=[578056] centers=[[121.53635720454548,31.212127111363635]], counts=[274670] centers=[[121.52864429887637,31.225297347865173]], counts=[400688]
     */
    // detail record 增加四大區域與分群群組
    var spatialDetails: RDD[spatialDetail] = details.
      mapPartitions{ ite =>
        ite.flatMap{ case (imsi, date, hour, vec) =>
          areas.find{ case (idx, area) => vec(0) > area(0) && vec(0) < area(1) && vec(1) > area(2) && vec(1) < area(3) } match {
            case None => None
            case Some((idx, area)) => Some( spatialDetail(imsi, date, hour, vec(0), vec(1), idx, (kmodels(idx)).predict(vec)) ) } } }.
      cache
    /*
    println(spatialDetails.take(5).map{ record => spatialDetail2String(record) }.mkString("\n"))
99e7e093f00fa32f36b051c18de98380,20151229,13,121.5225,31.25561001,1,6
99e7e093f00fa32f36b051c18de98380,20151229,14,121.46884,31.19959001,2,7
99e7e093f00fa32f36b051c18de98380,20151229,15,121.521368,31.27020701,1,6
99e7e093f00fa32f36b051c18de98380,20151231,16,121.4396,31.19247001,2,2
40fe7f8e47c3e89a4d4551c855df46f8,20160106,8,121.44609,31.20208001,2,6
    spatialDetails.count = 19722375
     */
    // 根據四大區域區分
    val spatialDetailsByArea: immutable.Map[Int, RDD[spatialDetail]] = areas.
      map{ case (idx, area) =>
        (idx, spatialDetails.filter{ spatialDetail => spatialDetail.areaid == idx }) }
    /*
    detailsAreaClusterByArea.map{ case (idx, details) => f"${idx},${details.count()}" }.mkString("\n")
0,5537069 1,2957485 2,7918600 3,3309221
     */
    // 儲存
    spatialDetailsByArea.foreach{ case (idx, details) =>
      path = f"/home/leoricklin/dataset/woplus/spatial.detail/${idx}%02d"
      saveSpatialDetail(sc, details, path) }
    /*
$ more /home/leoricklin/dataset/woplus/spatial.detail/00
bf3f04034044fd5c9d5f9445c3a3eda3,20160102,9,121.4837113,31.2601311,0,8
bf3f04034044fd5c9d5f9445c3a3eda3,20151230,10,121.47773,31.24814,0,1
bf3f04034044fd5c9d5f9445c3a3eda3,20151229,10,121.47773,31.24814,0,1
5870107caa6fff2989f3cd57bfc14daa,20151230,0,121.46861,31.25445001,0,7
5870107caa6fff2989f3cd57bfc14daa,20151230,1,121.46861,31.25445001,0,7
5870107caa6fff2989f3cd57bfc14daa,20151230,2,121.46861,31.25445001,0,7
     */
    //
    uniqXyByAreas.foreach{ case (idx, vecs) => vecs.unpersist(true) }
    xyByAreas.foreach{ case (idx, vecs) => vecs.unpersist(true) }
    details.unpersist(true)
  }

  def reportA(sc:SparkContext, areas:Map[Int, Seq[Double]]) = {
    val sqlContext = new SQLContext(sc)
    import sqlContext.implicits._
    // 載入用戶位置紀錄
    val ite = areas.toIterator
    var path = f"/home/leoricklin/dataset/woplus/spatial.detail/${ite.next()._1}%02d"
    var spatialDetails = loadSpatialDetail(sc, path)
    ite.foreach{ case (idx, area) =>
      path = f"/home/leoricklin/dataset/woplus/spatial.detail/${idx}%02d"
      spatialDetails = spatialDetails.union(loadSpatialDetail(sc, path))
    }
    spatialDetails.cache()
    spatialDetails.getStorageLevel.useMemory
    spatialDetails.count // 19722375
    spatialDetails.filter( spdetail => spdetail.imei.equals("bf3f04034044fd5c9d5f9445c3a3eda3")).
      collect().
      map{ spdetail => spatialDetail2Str(spdetail)}.mkString("\n")
    /*
bf3f04034044fd5c9d5f9445c3a3eda3,20160102,9,121.4837113,31.2601311,0,8
bf3f04034044fd5c9d5f9445c3a3eda3,20151230,10,121.47773,31.24814,0,1
bf3f04034044fd5c9d5f9445c3a3eda3,20151229,10,121.47773,31.24814,0,1
     */
    spatialDetails.take(10).
      map{ spdetail => spatialDetail2Str(spdetail)}.mkString("\n")
    /*
    spatialDetailsByArea.map{ case (idx, details) => f"${idx},${details.partitions.size}" }.mkString(" ")
    // 0,6 1,6 2,6 3,6
    spatialDetailsByArea.map{ case (idx, details) => f"${idx},${details.count()}" }.mkString(" ")
    // 0,5537069 1,2957485 2,7918600 3,3309221
     */
    spatialDetails.toDF().registerTempTable("spatialdetail")
    var df = sqlContext.sql("select imei from spatialdetail limit 1")
    /*
= [imei: string, date: string, hour: int, x: double, y: double, areaid: int, clusterid: int]
     */
    var result = df.collect()
    println(result.map{ row => spatialDetail2Str(row2spatialDetail(row)) }.mkString("\n"))
    result.map{ row => row.getString(0) }.mkString("\n")
    /*
bf3f04034044fd5c9d5f9445c3a3eda3,20160102,9,121.4837113,31.2601311,0,8
     */
    // 列出用戶位置紀錄的日期
    df = sqlContext.sql("select date from spatialdetail").distinct()
    result = df.collect()
    println(result.map{ row => row.getString(0) }.mkString(" "))
    /*
20151227 20151228 20151229 20151230 20151231 20160101 20160102 20160103 20160104 20160105 20160106
     */
    // 載入用戶標籤紀錄
    //
    df = sqlContext.sql("select s.imei, s.date, s.hour, s.x, s.y, s.areaid, s.clusterid, u.features" +
      " from" +
      "  ( select * from spatialdetail where date = '20160101' and hour = 10 and areaid = 0 and clusterid = 0" +
      "  ) s" +
      " inner join usertag u" +
      " on s.imei = u.imei")
    /*
= [imei: string, date: string, hour: int, x: double, y: double,
   areaid: int, clusterid: int, features: array<double>
     */
    result = df.collect()
    result.
      map{ row =>
        val spatial = Row(
          row.getString(0),row.getString(1),row.getInt(2),row.getDouble(3),row.getDouble(4),
          row.getInt(5),row.getInt(6))
        val feature = row.getSeq(7).mkString(",")
        f"${spatialDetail2Str(row2spatialDetail(spatial))},${feature}" }.
      mkString("\n")
    //
    spatialDetails.unpersist(true)


  }

}

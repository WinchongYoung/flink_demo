package hotItemsanalysis

import hotItemsanalysis.UvWithBloom.dateFormat
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.triggers.{CountTrigger, Trigger, TriggerResult}
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector
import redis.clients.jedis.Jedis

import java.lang
import java.sql.Date
import java.text.SimpleDateFormat

object UvWithBloom {

  val dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(1)

    // 用相对路径定义数据源
    val dataStream = env.readTextFile("src/main/resources/UserBehavior.csv").
      map(data => {
        val dataArray = data.split(",")
        UserBehavior(dataArray(0).trim.toLong, dataArray(1).trim.toLong, dataArray(2).trim.toInt, dataArray(3).trim, dataArray(4).trim.toLong)
      })
      .assignAscendingTimestamps(_.timestamp * 1000L)
      .filter(_.behavior == "pv") // 只统计pv操作
      .map(data => ("dummyKey", data.userId))
      .keyBy(_._1)
      .timeWindow(Time.hours(1))
      .trigger(new MyTrigger)
      .process(new UvCountWithBloom())

    dataStream.print()

    env.execute("uv with bloom job")
  }
}

// 自定义窗口触发器
class MyTrigger() extends Trigger[(String, Long), TimeWindow] {
  /** * 事件时间计数器触发 ** */
  override def onEventTime(time: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = TriggerResult.CONTINUE

  /** * 处理时间计数器触发 ** */
  override def onProcessingTime(time: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = TriggerResult.CONTINUE

  /** 执行任何需要清除的相应窗口 * */
  override def clear(window: TimeWindow, ctx: Trigger.TriggerContext): Unit = {}

  /** * 新的数据进入窗口时触发 ** */
  override def onElement(element: (String, Long), timestamp: Long, window: TimeWindow, ctx: Trigger.TriggerContext): TriggerResult = {
    // 每来一条数据，就直接触发窗口操作，并清空所有窗口状态
    TriggerResult.FIRE_AND_PURGE
  }
}

// 定义一个布隆过滤器
class Bloom(size: Long) extends Serializable {
  // 位图的总大小，默认16M
  private val cap = if (size > 0) size else 1 << 27

  // 定义hash函数
  def hash(value: String, seed: Int): Long = {
    var result = 0L
    for (i <- 0 until value.length) {
      result = result * seed + value.charAt(i)
    }
    result & (cap - 1)
  }
}

class UvCountWithBloom() extends ProcessWindowFunction[(String, Long), UvCountNew, String, TimeWindow] {
  // 定义redis连接
  lazy val jedis = new Jedis("localhost", 6379)
  lazy val bloom = new Bloom(1 << 29)

  override def process(key: String, context: Context, elements: Iterable[(String, Long)], out: Collector[UvCountNew]): Unit = {
    // 位图的存储方式，key是windowEnd，value是bitmap
    val storeKey: String = context.window.getEnd.toString
    var count = 0L
    // 把每个窗口的uv count值也存入名为count的redis表，存放内容为（windowEnd -> uvCount），所以要先从redis中读取
    if (jedis.hget("count", storeKey) != null) {
      count = jedis.hget("count", storeKey).toLong
    }
    // 用布隆过滤器判断当前用户是否已经存在
    val userId: String = elements.last._2.toString
    val offset: Long = bloom.hash(userId, 61)
    // 定义一个标识位，判断redis位图中有没有这一位
    val isExist: lang.Boolean = jedis.getbit(storeKey, offset)
    val date: String = dateFormat.format(new Date(storeKey.toLong))
    if (!isExist) {
      // 如果不存在，位图对应位置1，count + 1
      jedis.setbit(storeKey, offset, true)
      jedis.hset("count", storeKey, (count + 1).toString)
      out.collect(UvCountNew(date, count + 1))
    } else {
      out.collect(UvCountNew(date, count))
    }
  }
}


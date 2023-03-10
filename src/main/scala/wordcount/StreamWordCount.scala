package wordcount

import org.apache.flink.api.java.utils.ParameterTool
import org.apache.flink.streaming.api.scala._

object StreamWordCount {
  def main(args: Array[String]): Unit = {

    val params = ParameterTool.fromArgs(args)
    val host: String = "127.0.0.1"
    val port: Int = 7777

    // 创建一个流处理的执行环境
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    //    env.setParallelism(1)
    //    env.disableOperatorChaining()

    // 接收socket数据流
    val textDataStream = env.socketTextStream(host, port)

    // 逐一读取数据，分词之后进行wordcount
    val wordCountDataStream = textDataStream.flatMap(_.split("\\s"))
      .filter(_.nonEmpty).startNewChain()
      .map((_, 1))
      .keyBy(0)
      .sum(1)

    // 打印输出
    wordCountDataStream.print().setParallelism(1)

    // 执行任务
    env.execute("stream word count job")
  }
}

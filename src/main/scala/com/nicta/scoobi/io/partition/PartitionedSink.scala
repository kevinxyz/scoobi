/**
 * Copyright 2011,2012 National ICT Australia Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.nicta
package scoobi
package io
package partition

import com.nicta.scoobi.core._
import org.apache.hadoop.io.NullWritable
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.hadoop.mapreduce.lib.output.{FileOutputCommitter, FileOutputFormat}
import org.apache.hadoop.mapreduce._
import com.nicta.scoobi.impl.io.Files
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.io.compress.CompressionCodec
import org.apache.hadoop.io.SequenceFile.CompressionType
import com.nicta.scoobi.impl.ScoobiConfigurationImpl
import com.nicta.scoobi.core.Compression
import com.nicta.scoobi.impl.util.DistCache
import java.net.URI
import com.nicta.scoobi.impl.mapreducer.ChannelOutputFormat

case class PartitionedSink[P, K, V, B](
  subsink: DataSink[K, V, B],
  format: Class[_ <: PartitionedOutputFormat[P, K, V]],
  path: String,
  partition: P => String,
  overwrite: Boolean = false, check: Sink.OutputCheck = Sink.defaultOutputCheck, compression: Option[Compression] = None) extends DataSink[P, (K, V), (P, B)] {

  private lazy val logger = LogFactory.getLog("scoobi.PartitionedSink")

  private val output = new Path(path)

  def outputFormat(implicit sc: ScoobiConfiguration) = format
  // hack to keep actual persisted classes
  def outputKeyClass(implicit sc: ScoobiConfiguration) = subsink.outputKeyClass.asInstanceOf[Class[P]]
  def outputValueClass(implicit sc: ScoobiConfiguration) = subsink.outputValueClass.asInstanceOf[Class[(K, V)]]

  def outputCheck(implicit sc: ScoobiConfiguration) {
    check(output, overwrite, sc)
  }

  def outputPath(implicit sc: ScoobiConfiguration) = Some(output)

  override def outputConfigure(job: Job)(implicit sc: ScoobiConfiguration) {
    // on a cluster the partition function needs to be distributed when configuring the job
    // otherwise several mappers will try to write at the same time
    if (sc.isRemote) distributePartitionFunction
  }

  override def outputSetup(implicit sc: ScoobiConfiguration) {
    super.outputSetup(sc)

    if (Files.pathExists(output)(sc.configuration) && overwrite) {
      logger.info("Deleting the pre-existing output path: " + output.toUri.toASCIIString)
      Files.deletePath(output)(sc.configuration)
    }
    // locally the partition function needs to be distributed
    // when setting up the output channels
    if (!sc.isRemote) distributePartitionFunction
  }

  private def distributePartitionFunction(implicit sc: ScoobiConfiguration) = {
    DistCache.pushObject[P => String](sc.configuration, partition, PartitionedSink.functionTag(sc.configuration, outputPath.getOrElse(id).toString))
  }

  lazy val outputConverter = new OutputConverter[P, (K, V), (P, B)] {
    def toKeyValue(x: (P, B))(implicit configuration: Configuration) = (x._1, subsink.outputConverter.toKeyValue(x._2))
  }

  def compressWith(codec: CompressionCodec, compressionType: CompressionType = CompressionType.BLOCK) = copy(compression = Some(Compression(codec, compressionType)))

  override def toString = getClass.getSimpleName+": "+outputPath(new ScoobiConfigurationImpl).getOrElse("none")
}

object PartitionedSink {

  /**
   * we need a specific tag to save the partition function in the distributed cache
   * we use the mapred.work.output.dir property to make sure that each function will be associated
   * to the correct PartitionedTextOutputFormat
   */
  def functionTag(configuration: Configuration, defaultWorkDir: String = "-") = {
    val outputDir = configuration.get("mapred.work.output.dir", defaultWorkDir)
    val withoutProtocol = new URI(outputDir).getPath.replace("/", "-")
    "pathPartitionFunction_"+withoutProtocol
  }

}

/**
 * This format creates a new record writer for each different path that's generated by the partition function
 * Each record writer defines a specific OutputCommitter that will define a different work directory for a given key.
 *
 * All the generated paths will be created under temporary dir/sink id in order to collect them
 * more rapidly with just a rename of directories (see OutputChannel)
 */
abstract class PartitionedOutputFormat[P, K, V] extends FileOutputFormat[P, (K, V)] {

  private val recordWriters =
    new collection.mutable.HashMap[String, RecordWriter[K, V]]


  def getRecordWriter(context: TaskAttemptContext): RecordWriter[P, (K, V)] = {
    val partitionFunctionTag = PartitionedSink.functionTag(context.getConfiguration)
    val partitionFunction = DistCache.pullObject[P => String](context.getConfiguration, partitionFunctionTag)

    // get the work dir by using the FileOutputCommitter in mapreduce.lib
    val outputDir = FileOutputFormat.getOutputPath(context)
    val outputCommitter = new FileOutputCommitter(outputDir, context)
    val workDir = outputCommitter.getWorkPath
    val sinkId = context.getConfiguration.get("mapreduce.output.basename").split("-").drop(1).headOption.map(_.replace(s"/${ChannelOutputFormat.basename}", "")).
      getOrElse(throw new Exception(s"malformed output basename, it should be ch<tag>-<sink id>/${ChannelOutputFormat.basename}"))

    new RecordWriter[P, (K, V)] {

      def write(partition: P, kv: (K, V)) {
        val (key, value) = kv
        val finalPath = generatePathForKeyValue(sinkId, partition, workDir, partitionFunction)(context.getConfiguration)
        val rw = recordWriters.get(finalPath) match {
          case None    => val newWriter = getBaseRecordWriter(context, new Path(finalPath)); recordWriters.put(finalPath, newWriter); newWriter
          case Some(x) => x
        }
        rw.write(key, value)
      }

      def close(context: TaskAttemptContext) {
        recordWriters.values.foreach(_.close(context))
        recordWriters.clear
      }
    }
  }

  protected def generatePathForKeyValue(sinkId: String, partition: P, workDir: Path, partitionFunction: Option[P => String])(configuration: Configuration): String = {
    val sb = new StringBuilder
    sb.append(workDir)
    sb.append("/")
    sb.append(sinkId)
    sb.append("/")
    sb.append(partitionFunction.fold(partition.toString)(_(partition)))
    sb.toString
  }

  protected def getBaseRecordWriter(context: TaskAttemptContext, path: Path): RecordWriter[K, V]
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import org.apache.spark.annotation.{Experimental, DeveloperApi}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.{InternalRow, optimizer}
import org.apache.spark.sql.{SQLContext, Row}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan

/**
 * :: Experimental ::
 * The primary workflow for executing relational queries using Spark.  Designed to allow easy
 * access to the intermediate phases of query execution for developers.
 */
@Experimental
class QueryExecution(val sqlContext: SQLContext, val logical: LogicalPlan) {
  val analyzer = sqlContext.analyzer
  val optimizer = sqlContext.optimizer
  val planner = sqlContext.planner
  val cacheManager = sqlContext.cacheManager
  val prepareForExecution = sqlContext.prepareForExecution

  def assertAnalyzed(): Unit = analyzer.checkAnalysis(analyzed)

  lazy val analyzed: LogicalPlan = analyzer.execute(logical)
  lazy val withCachedData: LogicalPlan = {
    assertAnalyzed()
    cacheManager.useCachedData(analyzed)
  }
  lazy val optimizedPlan: LogicalPlan = optimizer.execute(withCachedData)

  // TODO: Don't just pick the first one...
  lazy val sparkPlan: SparkPlan = {
    SparkPlan.currentContext.set(sqlContext)
    planner.plan(optimizedPlan).next()
  }
  // executedPlan should not be used to initialize any SparkPlan. It should be
  // only used for execution.
  lazy val executedPlan: SparkPlan = prepareForExecution.execute(sparkPlan)

  /** Internal version of the RDD. Avoids copies and has no schema */
  lazy val toRdd: RDD[InternalRow] = executedPlan.execute()

  protected def stringOrError[A](f: => A): String =
    try f.toString catch { case e: Throwable => e.toString }

  def simpleString: String =
    s"""== Physical Plan ==
       |${stringOrError(executedPlan)}
      """.stripMargin.trim

  override def toString: String = {
    def output =
      analyzed.output.map(o => s"${o.name}: ${o.dataType.simpleString}").mkString(", ")

    // TODO previously will output RDD details by run (${stringOrError(toRdd.toDebugString)})
    // however, the `toRdd` will cause the real execution, which is not what we want.
    // We need to think about how to avoid the side effect.
    s"""== Parsed Logical Plan ==
       |${stringOrError(logical)}
        |== Analyzed Logical Plan ==
        |${stringOrError(output)}
        |${stringOrError(analyzed)}
        |== Optimized Logical Plan ==
        |${stringOrError(optimizedPlan)}
        |== Physical Plan ==
        |${stringOrError(executedPlan)}
        |Code Generation: ${stringOrError(executedPlan.codegenEnabled)}
        |== RDD ==
      """.stripMargin.trim
  }
}

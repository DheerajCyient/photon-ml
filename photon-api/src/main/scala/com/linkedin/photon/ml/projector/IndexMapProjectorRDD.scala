/*
 * Copyright 2017 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.projector

import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import com.linkedin.photon.ml.Types.REId
import com.linkedin.photon.ml.data.{LabeledPoint, RandomEffectDataSet}
import com.linkedin.photon.ml.model.Coefficients
import com.linkedin.photon.ml.normalization.NormalizationContext
import com.linkedin.photon.ml.spark.RDDLike
import com.linkedin.photon.ml.supervised.model.GeneralizedLinearModel
import com.linkedin.photon.ml.util.{BroadcastWrapper, PhotonNonBroadcast, VectorUtils}

/**
 * A class that holds the projectors for a sharded data set.
 *
 * @param indexMapProjectorRDD The projectors
 */
protected[ml] class IndexMapProjectorRDD private (indexMapProjectorRDD: RDD[(String, IndexMapProjector)])
  extends RandomEffectProjector
  with RDDLike {

  /**
   *
   * @param randomEffectDataSet The input sharded data set in the original space
   * @return The sharded data set in the projected space
   */
  override def projectRandomEffectDataSet(randomEffectDataSet: RandomEffectDataSet): RandomEffectDataSet = {

    val activeData = randomEffectDataSet.activeData
    val passiveDataOption = randomEffectDataSet.passiveDataOption
    val passiveDataRandomEffectIdsOption = randomEffectDataSet.passiveDataRandomEffectIdsOption
    val projectedActiveData =
      activeData
        // Make sure the activeData retains its partitioner, especially when the partitioner of featureMaps is
        // not the same as that of activeData
        .join(indexMapProjectorRDD, activeData.partitioner.get)
        .mapValues { case (localDataSet, projector) => localDataSet.projectFeatures(projector) }

    val projectedPassiveData =
      if (passiveDataOption.isDefined) {
        val passiveDataRandomEffectIds = passiveDataRandomEffectIdsOption.get
        val projectorsForPassiveData = indexMapProjectorRDD.filter { case (randomEffectId, _) =>
          passiveDataRandomEffectIds.value.contains(randomEffectId)
        }.collectAsMap()

        val projectorsForPassiveDataBroadcast = passiveDataOption.get.sparkContext.broadcast(projectorsForPassiveData)
        val result = passiveDataOption.map {
          _.mapValues { case (shardId, LabeledPoint(response, features, offset, weight)) =>
            val projector = projectorsForPassiveDataBroadcast.value(shardId)
            val projectedFeatures = projector.projectFeatures(features)

            (shardId, LabeledPoint(response, projectedFeatures, offset, weight))
          }
        }

        projectorsForPassiveDataBroadcast.unpersist()
        result
      } else {
        None
      }

    randomEffectDataSet.update(projectedActiveData, projectedPassiveData)
  }

  /**
   *
   * @param coefficientsRDD
   * @return The [[RDD]] of [[Coefficients]] in the original space
   */
  override def projectCoefficientsRDD(
      coefficientsRDD: RDD[(String, GeneralizedLinearModel)]): RDD[(String, GeneralizedLinearModel)] =
    coefficientsRDD
      .join(indexMapProjectorRDD)
      .mapValues { case (model, projector) =>
        val oldCoefficients = model.coefficients

        model.updateCoefficients(
          Coefficients(
            projector.projectCoefficients(oldCoefficients.means),
            oldCoefficients.variancesOption.map(projector.projectCoefficients)))
      }

  /**
    *
    * @param normalizationContext: broadcast global NormalizationContext
    * @return The sharded NormalizationContext in the projected space
    */
  def projectNormalizationRDD(
      normalizationContext: BroadcastWrapper[NormalizationContext]) : RDD[(REId, BroadcastWrapper[NormalizationContext])] = {
    indexMapProjectorRDD.mapValues {
      case projector => {
        val originalNormalizationContext = normalizationContext.value
        val factors = originalNormalizationContext.factors.map(factors => projector.projectFeatures(factors))
        val shifts = originalNormalizationContext.shifts.map(shifts => projector.projectFeatures(shifts))
        val interceptId = originalNormalizationContext.interceptId.map(interceptId =>
          projector.originalToProjectedSpaceMap(interceptId))
        val norm = new NormalizationContext(factors, shifts, interceptId)
        PhotonNonBroadcast(norm)
      }
    }
  }

  /**
   * Get the Spark context.
   *
   * @return The Spark context
   */
  override def sparkContext: SparkContext = indexMapProjectorRDD.sparkContext

  /**
   * Assign a given name to [[indexMapProjectorRDD]].
   *
   * @note Not used to reference models in the logic of photon-ml, only used for logging currently.
   *
   * @param name The parent name for all [[RDD]]s in this class
   * @return This object with the name of [[indexMapProjectorRDD]] assigned
   */
  override def setName(name: String): IndexMapProjectorRDD = {

    indexMapProjectorRDD.setName(name)

    this
  }

  /**
   * Set the storage level of [[indexMapProjectorRDD]], and persist their values across the cluster the first time they are
   * computed.
   *
   * @param storageLevel The storage level
   * @return This object with the storage level of [[indexMapProjectorRDD]] set
   */
  override def persistRDD(storageLevel: StorageLevel): IndexMapProjectorRDD = {

    if (!indexMapProjectorRDD.getStorageLevel.isValid) indexMapProjectorRDD.persist(storageLevel)

    this
  }

  /**
   * Mark [[indexMapProjectorRDD]] as non-persistent, and remove all blocks for them from memory and disk.
   *
   * @return This object with [[indexMapProjectorRDD]] marked non-persistent
   */
  override def unpersistRDD(): IndexMapProjectorRDD = {

    if (indexMapProjectorRDD.getStorageLevel.isValid) indexMapProjectorRDD.unpersist()

    this
  }

  /**
   * Materialize [[indexMapProjectorRDD]] (Spark [[RDD]]s are lazy evaluated: this method forces them to be evaluated).
   *
   * @return This object with [[indexMapProjectorRDD]] materialized
   */
  override def materialize(): IndexMapProjectorRDD = {

    materializeOnce(indexMapProjectorRDD)

    this
  }
}

object IndexMapProjectorRDD {

  /**
   * Generate index map based RDD projectors.
   *
   * @param randomEffectDataSet The input random effect data set
   * @return The generated index map based RDD projectors
   */
  protected[ml] def buildIndexMapProjector(
      randomEffectDataSet: RandomEffectDataSet): IndexMapProjectorRDD = {

    val originalSpaceDimension = randomEffectDataSet
      .activeData
      .map { case (_, ds) => ds.dataPoints.head._2.features.length }
      .take(1)(0)

    // Collect active indices for the active dataset
    val activeIndices = randomEffectDataSet
      .activeData
      .mapValues(ds => ds
        .dataPoints
        .map(_._2.features)
        .flatMap(VectorUtils.getActiveIndices)
        .toSet)

    // Collect active indices for the passive dataset
    val passiveIndicesOption = randomEffectDataSet
      .passiveDataOption
      .map { passiveData =>
        passiveData
          .map {
            case (_, (reId, labeledPoint)) => (reId, labeledPoint.features)
          }
          .mapValues(VectorUtils.getActiveIndices)
      }

    // Union them, and fold the results into (reId, indices) tuples
    val indices = passiveIndicesOption
      .map { passiveIndices =>
        activeIndices
          .union(passiveIndices)
          .foldByKey(Set.empty[Int])(_ ++ _)
      }
      .getOrElse(activeIndices)

    val indexMapProjectors = indices.mapValues(indexSet =>
      new IndexMapProjector(indexSet.zipWithIndex.toMap, originalSpaceDimension, indexSet.size))

    new IndexMapProjectorRDD(indexMapProjectors)
  }
}

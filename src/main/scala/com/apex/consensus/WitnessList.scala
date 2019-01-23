/*
 * Copyright  2018 APEX Technologies.Co.Ltd. All rights reserved.
 *
 * FileName: WitnessList.scala
 *
 * @author: shan.huang@chinapex.com: 18-7-18 下午4:06@version: 1.0
 */

package com.apex.consensus

import java.io.{DataInputStream, DataOutputStream}

import com.apex.crypto.{UInt160, UInt256}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

class WitnessList(val witnesses: Array[WitnessInfo],
                  val generateInBlock: UInt256,
                  val version: Int = 0x01) extends com.apex.common.Serializable {

  override def serialize(os: DataOutputStream): Unit = {
    import com.apex.common.Serializable._

    os.writeInt(version)
    os.writeSeq(witnesses)
    os.write(generateInBlock)
  }

  def contain(witness: UInt160): Boolean = {
    witnesses.find(w => w.addr == witness).isDefined
  }

  def sortByLocation() = {
    witnesses.sortWith((w1, w2) => {
      if (w1.longitude > w2.longitude)
        true
      else
        false // TODO
    })
  }

  def findLeastVotes(): UInt160 = {
    WitnessList.sortByVote(witnesses).last.addr
  }

}

object WitnessList {

  def sortByVote(witnesses: Array[WitnessInfo]) = {
    witnesses.sortWith((w1, w2) => {
      if (w1.voteCounts > w2.voteCounts)
        true
      else
        false // TODO
    })
  }

  def getLeastVote(witnesses: Array[WitnessInfo]): WitnessInfo = {
    sortByVote(witnesses).last
  }

  def removeLeastVote(witnesses: Array[WitnessInfo]): mutable.Map[UInt160, WitnessInfo] = {
    val newWitnesses = mutable.Map.empty[UInt160, WitnessInfo]
    val sorted = sortByVote(witnesses)
    for (i <- 0 to sorted.size - 2) {
      val w = sorted(i)
      newWitnesses.update(w.addr, w)
    }
    newWitnesses
  }

  def deserialize(is: DataInputStream): WitnessList = {
    import com.apex.common.Serializable._

    val version = is.readInt()
    val witnesses = is.readSeq(WitnessInfo.deserialize)
    val generateInBlock = is.readObj(UInt256.deserialize)

    new WitnessList(witnesses.toArray, generateInBlock, version)
  }
}
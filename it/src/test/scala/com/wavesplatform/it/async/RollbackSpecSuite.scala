package com.wavesplatform.it.async

import com.typesafe.config.{Config, ConfigFactory}
import com.wavesplatform.it.api.AsyncHttpApi._
import com.wavesplatform.it.transactions.NodesFromDocker
import com.wavesplatform.it.util._
import com.wavesplatform.state2.{BooleanDataEntry, LongDataEntry}
import com.wavesplatform.it.{NodeConfigs, TransferSending, WaitForHeight2}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.{FreeSpec, Matchers}

import scala.concurrent.Await.result
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.traverse
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.Random

class RollbackSpecSuite
    extends FreeSpec
    with ScalaFutures
    with IntegrationPatience
    with Matchers
    with TransferSending
    with WaitForHeight2
    with NodesFromDocker {
  // there are nodes with big and small balances to reduce the number of forks
  private val nonGeneratingNodesConfig = ConfigFactory.parseString("Agate.miner.enable = no")

  override protected val nodeConfigs: Seq[Config] = Seq(
    NodeConfigs.Default.last,
    nonGeneratingNodesConfig.withFallback(Random.shuffle(NodeConfigs.Default.init).head)
  )

  private val nodeAddresses     = nodeConfigs.map(_.getString("address")).toSet
  private val transactionsCount = 190

  "Apply the same transfer transactions twice with return to UTX" in {
    val waitBlocks = 10
    result(
      for {
        startHeight <- Future.traverse(nodes)(_.height).map(_.min)

        _ <- processRequests(generateTransfersToRandomAddresses(transactionsCount, nodeAddresses))

        stateAfterFirstTry <- nodes.head.debugStateAt(startHeight + waitBlocks)

        _ <- nodes.tail.head.rollback(1)
        _ <- nodes.head.rollback(startHeight)

        stateAfterSecondTry <- nodes.head.debugStateAt(startHeight + waitBlocks)
      } yield {
        stateAfterFirstTry should contain theSameElementsAs stateAfterSecondTry
      },
      5.minutes
    )
  }

  "Just rollback transactions" in {
    val waitBlocks = 8
    result(
      for {
        startHeight <- Future.traverse(nodes)(_.height).map(_.min)

        requests = generateTransfersToRandomAddresses(transactionsCount, nodeAddresses)

        stateBeforeApply <- nodes.head.debugStateAt(startHeight + waitBlocks)

        _ <- processRequests(requests)
        _ <- nodes.head.waitFor[Int]("empty utx")(_.utxSize, _ == 0, 1.second)
        _ <- traverse(nodes)(_.rollback(startHeight, returnToUTX = false))
        _ <- nodes.head.utx.map(_ shouldBe 'empty)
        _ <- traverse(nodes)(_.waitForHeight(startHeight + 1))

        stateAfterApply <- nodes.head.debugStateAt(startHeight + waitBlocks)
      } yield {
        stateBeforeApply should contain theSameElementsAs stateAfterApply
      },
      5.minutes
    )
  }

  "Alias transaction rollback should work fine" in {
    val alias = "test_alias4"

    val f = for {
      startHeight     <- Future.traverse(nodes)(_.height).map(_.max)
      aliasTxId       <- nodes.head.createAlias(nodes.head.address, alias, 1.Agate).map(_.id)
      _               <- Future.traverse(nodes)(_.waitForTransaction(aliasTxId))
      _               <- Future.traverse(nodes)(_.waitForHeight(startHeight + 1))
      _               <- Future.traverse(nodes)(_.rollback(startHeight - 1, returnToUTX = false))
      _               <- Future.traverse(nodes)(_.waitForHeight(startHeight + 1))
      secondAliasTxId <- nodes.head.createAlias(nodes.head.address, alias, 1.Agate).map(_.id)
      _               <- Future.traverse(nodes)(_.waitForTransaction(secondAliasTxId))
    } yield succeed

    Await.result(f, 1.minute)
  }

  "Data transaction rollback" in {
    val node   = nodes.head
    val entry1 = LongDataEntry("1", 0)
    val entry2 = BooleanDataEntry("2", true)
    val entry3 = LongDataEntry("1", 1)

    val f = for {
      startHeight <- Future.traverse(nodes)(_.height).map(_.max)
      tx1         <- node.putData(node.address, List(entry1), 100000).map(_.id)
      _           <- Future.traverse(nodes)(_.waitForTransaction(tx1))
      _           <- Future.traverse(nodes)(_.waitForHeight(startHeight + 1))
      tx2         <- node.putData(node.address, List(entry2, entry3), 100000).map(_.id)
      _           <- Future.traverse(nodes)(_.waitForTransaction(tx2))
      _           <- Future.traverse(nodes)(_.waitForHeight(startHeight + 2))
      data2       <- node.getData(node.address)
      _ = assert(data2 == List(entry3, entry2))

      _     <- Future.traverse(nodes)(_.rollback(startHeight + 1, returnToUTX = false))
      _     <- Future.traverse(nodes)(_.waitForHeight(startHeight + 1))
      data1 <- node.getData(node.address)
      _ = assert(data1 == List(entry1))

      _     <- Future.traverse(nodes)(_.rollback(startHeight, returnToUTX = false))
      _     <- Future.traverse(nodes)(_.waitForHeight(startHeight))
      data0 <- node.getData(node.address)
      _ = assert(data0 == List.empty)
    } yield succeed

    Await.result(f, 3.minutes)
  }
}

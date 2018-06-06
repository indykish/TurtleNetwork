package com.wavesplatform.state2

import java.io.File
import java.nio.file.Files

import com.wavesplatform.TransactionGenBase
import com.wavesplatform.database.LevelDBWriter
import com.wavesplatform.db.LevelDBFactory
import com.wavesplatform.settings.FunctionalitySettings
import com.wavesplatform.state2.diffs.BlockDiffer
import org.iq80.leveldb.{DB, Options}
import org.openjdk.jmh.annotations.{Setup, TearDown}
import org.scalacheck.Gen
import scorex.account.PrivateKeyAccount
import scorex.block.Block
import scorex.lagonaki.mocks.TestBlock
import scorex.transaction.{GenesisTransaction, Transaction}

trait BaseState extends TransactionGenBase {
  import BaseState._

  private val fs: FunctionalitySettings = updateFunctionalitySettings(FunctionalitySettings.TESAgateET)
  private var db: DB                    = _

  private var _state: LevelDBWriter           = _
  private var _richAccount: PrivateKeyAccount = _
  private var _lastBlock: Block               = _

  def state: LevelDBWriter           = _state
  def richAccount: PrivateKeyAccount = _richAccount
  def lastBlock: Block               = _lastBlock

  protected def updateFunctionalitySettings(base: FunctionalitySettings): FunctionalitySettings = base

  protected def txGenP(sender: PrivateKeyAccount, ts: Long): Gen[Transaction]

  private def genBlock(base: Block, sender: PrivateKeyAccount): Gen[Block] =
    for {
      transferTxs <- Gen.sequence[Vector[Transaction], Transaction]((1 to TxsInBlock).map { i =>
        txGenP(sender, base.timestamp + i)
      })
    } yield
      TestBlock.create(
        time = transferTxs.last.timestamp,
        ref = base.uniqueId,
        txs = transferTxs
      )

  private val initGen: Gen[(PrivateKeyAccount, Block)] = for {
    rich <- accountGen
  } yield {
    val genesisTx = GenesisTransaction.create(rich, waves(100000000L), System.currentTimeMillis() - 10000).right.get
    (rich, TestBlock.create(time = genesisTx.timestamp, Seq(genesisTx)))
  }

  protected def nextBlock(txs: Seq[Transaction]): Block = TestBlock.create(
    time = txs.last.timestamp,
    ref = lastBlock.uniqueId,
    txs = txs
  )

  private def append(prev: Option[Block], next: Block): Unit = {
    val preconditionDiff = BlockDiffer.fromBlock(fs, state, state, prev, next).explicitGet()
    state.append(preconditionDiff, next)
  }

  protected def waves(n: Float): Long = (n * 100000000L).toLong

  def applyBlock(b: Block): Unit = {
    append(Some(lastBlock), b)
    _lastBlock = b
  }

  def genAndApplyNextBlock(): Unit = {
    val b = genBlock(lastBlock, richAccount).sample.get
    applyBlock(b)
  }

  @Setup
  def init(): Unit = {
    val dir     = Files.createTempDirectory("state-synthetic").toAbsolutePath.toString
    val options = new Options()
    options.createIfMissing(true)
    db = LevelDBFactory.factory.open(new File(dir), options)

    val (richAccount, genesisBlock) = initGen.sample.get
    _richAccount = richAccount

    _state = new LevelDBWriter(db, fs)
    append(None, genesisBlock)
    _lastBlock = genesisBlock
  }

  @TearDown
  override def close(): Unit = {
    super.close()
    db.close()
  }
}

object BaseState {
  private val TxsInBlock = 5000
}

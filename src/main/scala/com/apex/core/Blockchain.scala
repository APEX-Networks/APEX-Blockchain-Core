package com.apex.core

import java.time.Instant

import akka.actor.ActorRef
import com.apex.common.ApexLogging
import com.apex.consensus.ProducerUtil
import com.apex.crypto.Ecdsa.{PrivateKey, PublicKey, PublicKeyHash}
import com.apex.crypto.{BinaryData, Crypto, FixedNumber, MerkleTree, UInt160, UInt256}
import com.apex.settings.{ChainSettings, ConsensusSettings, RuntimeParas, Witness}

import scala.collection.{immutable, mutable}
import scala.collection.mutable.{ArrayBuffer, Set}

case class ChainInfo(id: String)

trait Blockchain extends Iterable[Block] with ApexLogging {
  def getChainInfo(): ChainInfo

  def getLatestHeader(): BlockHeader

  def getHeight(): Long

  def getHeadTime(): Long

  def headTimeSinceGenesis(): Long

  def getHeader(id: UInt256): Option[BlockHeader]

  def getHeader(index: Long): Option[BlockHeader]

  def getNextBlockId(id: UInt256): Option[UInt256]

  def getBlock(height: Long): Option[Block]

  def getBlock(id: UInt256): Option[Block]

  //def getBlockInForkBase(id: UInt256): Option[Block]

  def containsBlock(id: UInt256): Boolean

  //  def produceBlock(producer: PublicKey, privateKey: PrivateKey, timeStamp: Long,
  //                   transactions: Seq[Transaction]): Option[Block]

  def startProduceBlock(producer: Witness, blockTime: Long, stopProcessTxTime: Long): Unit

  def addTransaction(tx: Transaction): Boolean

  def produceBlockFinalize(): Option[Block]

  def isProducingBlock(): Boolean

  def tryInsertBlock(block: Block, doApply: Boolean): Boolean

  //
  //  def getTransaction(id: UInt256): Option[Transaction]
  //
  //  def containsTransaction(id: UInt256): Boolean

  //def verifyBlock(block: Block): Boolean

  //def verifyTransaction(tx: Transaction): Boolean

  def getBalance(address: UInt160): Option[FixedNumber]

  def getAccount(address: UInt160): Option[Account]

  def Id: String

  def close()
}

object Blockchain {
  //private var chain: LevelDBBlockchain = null

  //  final val Current: Blockchain = new LevelDBBlockchain()
  def populate(chainSettings: ChainSettings,
               consensusSettings: ConsensusSettings,
               runtimeParas: RuntimeParas,
               notification: Notification): LevelDBBlockchain = {
    new LevelDBBlockchain(chainSettings, consensusSettings, runtimeParas, notification)
  }

  //def getLevelDBBlockchain: LevelDBBlockchain = chain
}

class PendingState {
  var producer: Witness = _
  var blockTime: Long = _
  var startTime: Long = _
  var blockIndex: Long = _
  var stopProcessTxTime: Long = _
  var isProducingBlock = false

  val txs = ArrayBuffer.empty[Transaction]

  def set(producer: Witness, blockTime: Long, stopProcessTxTime: Long, blockIndex: Long) = {
    this.producer = producer
    this.blockTime = blockTime
    this.stopProcessTxTime = stopProcessTxTime
    this.startTime = Instant.now.toEpochMilli
    this.blockIndex = blockIndex
  }
}

class LevelDBBlockchain(chainSettings: ChainSettings,
                        consensusSettings: ConsensusSettings,
                        runtimeParas: RuntimeParas,
                        notification: Notification) extends Blockchain {

  log.info("LevelDBBlockchain starting")

  private val genesisProducerPrivKey = new PrivateKey(BinaryData(chainSettings.genesis.privateKey))

  log.info("creating BlockBase")

  private val blockBase = new BlockBase(chainSettings.blockBase)

  log.info("creating DataBase")

  private val dataBase = new DataBase(chainSettings.dataBase)

  log.info("creating ForkBase")

  private val forkBase = new ForkBase(
    chainSettings.forkBase,
    consensusSettings.initialWitness,
    onConfirmed,
    onSwitch)

  log.info("creating PeerBase")

  private val peerBase = new PeerBase(chainSettings.peerBase)
  val dbGasLimit =  peerBase.getGasLimit()
  if(dbGasLimit != null && dbGasLimit != None){
    runtimeParas.setAcceptGasLimit(dbGasLimit.get.longValue())
  }

  log.info("creating Genesis Block")

  private val minerCoinFrom = UInt160.Zero

  private val minerAward = FixedNumber.fromDecimal(chainSettings.minerAward)

  private val genesisBlock: Block = buildGenesisBlock()

  private val unapplyTxs = mutable.Map.empty[UInt256, Transaction]

  private var timeoutTx: Option[Transaction] = None

  private val pendingState = new PendingState

  populate()

  private def buildGenesisBlock(): Block = {
    val genesisTxs = ArrayBuffer.empty[Transaction]

    chainSettings.genesis.genesisCoinAirdrop.foreach(airdrop => {
      genesisTxs.append(new Transaction(TransactionType.Miner, minerCoinFrom,
        PublicKeyHash.fromAddress(airdrop.addr).get, "", FixedNumber.fromDecimal(airdrop.coins),
        0, consensusSettings.fingerprint(), FixedNumber.Zero, 0, BinaryData.empty))
    })

    val genesisBlockHeader: BlockHeader = BlockHeader.build(0,
      chainSettings.genesis.timeStamp.toEpochMilli, MerkleTree.root(genesisTxs.map(_.id)),
      UInt256.Zero, genesisProducerPrivKey)

    Block.build(genesisBlockHeader, genesisTxs)
  }

  override def Id: String = genesisBlock.id.toString

  override def iterator: Iterator[Block] = new BlockchainIterator(this)

  override def close() = {
    log.info("blockchain closing")
    blockBase.close()
    dataBase.close()
    forkBase.close()
    peerBase.close()
    log.info("blockchain closed")
  }

  override def getChainInfo(): ChainInfo = {
    ChainInfo(genesisBlock.id.toString)
  }

  override def getHeight(): Long = {
    forkBase.head.map(_.block.height).getOrElse(genesisBlock.height)
  }

  override def getHeadTime(): Long = {
    forkBase.head.map(_.block.timeStamp).getOrElse(0)
  }

  override def getLatestHeader(): BlockHeader = {
    forkBase.head.map(_.block.header).getOrElse(genesisBlock.header)
  }

  def getConfirmedHeader(): Option[BlockHeader] = {
    blockBase.head()
  }

  override def headTimeSinceGenesis(): Long = {
    getLatestHeader.timeStamp - genesisBlock.header.timeStamp
  }

  override def getHeader(id: UInt256): Option[BlockHeader] = {
    forkBase.get(id).map(_.block.header).orElse(blockBase.getBlock(id).map(_.header))
  }

  override def getHeader(height: Long): Option[BlockHeader] = {
    forkBase.get(height).map(_.block.header).orElse(blockBase.getBlock(height).map(_.header))
  }

  override def getNextBlockId(id: UInt256): Option[UInt256] = {
    var target: Option[UInt256] = None
    val block = getBlock(id)
    if (block.isDefined) {
      val nextBlock = getBlock(block.get.height() + 1)
      if (nextBlock.isDefined)
        target = Some(nextBlock.get.id())
    }
    if (target == None) {
      target = forkBase.getNext(id)
    }
    target
  }

  override def getBlock(id: UInt256): Option[Block] = {
    forkBase.get(id).map(_.block).orElse(blockBase.getBlock(id))
  }

  override def getBlock(height: Long): Option[Block] = {
    forkBase.get(height).map(_.block).orElse(blockBase.getBlock(height))
  }

  override def containsBlock(id: UInt256): Boolean = {
    forkBase.contains(id) || blockBase.containBlock(id)
  }

  def getTransactionFromMempool(txid: UInt256): Option[Transaction] = {
    pendingState.txs.find(tx => tx.id().equals(txid)).orElse(unapplyTxs.get(txid))
  }

  def getTransactionFromPendingTxs(txid: UInt256): Option[Transaction] = {
    pendingState.txs.find(tx => tx.id().equals(txid))
  }

  def getTransactionFromUnapplyTxs(txid: UInt256): Option[Transaction] = {
    unapplyTxs.get(txid)
  }

  override def startProduceBlock(producer: Witness, blockTime: Long, stopProcessTxTime: Long): Unit = {
    require(!isProducingBlock())
    val forkHead = forkBase.head.get
    pendingState.set(producer, blockTime, stopProcessTxTime, forkHead.block.height + 1)
    log.debug(s"start block at: ${pendingState.startTime}  blockTime=${blockTime}  stopProcessTxTime=${stopProcessTxTime}")

    val minerTx = new Transaction(TransactionType.Miner, minerCoinFrom,
      producer.pubkeyHash, "", minerAward,
      forkHead.block.height + 1,
      BinaryData(Crypto.randomBytes(8)), // add random bytes to distinct different blocks with same block index during debug in some cases
      FixedNumber.Zero, 0, BinaryData.empty
    )
    //isPendingBlock = true
    dataBase.startSession()

    val applied = applyTransaction(minerTx, producer.pubkeyHash, stopProcessTxTime, blockTime, forkHead.block.height + 1)
    require(applied)
    pendingState.txs.append(minerTx)
    pendingState.isProducingBlock = true

    if (timeoutTx.isDefined) {
      val oldTimeoutTx = timeoutTx.get

      // must set to null before call applyTransaction() because applyTransaction() may set it again
      timeoutTx = None

      log.info(s"try again for the old timeout tx ${oldTimeoutTx.id().shortString()}")

      // return value can be ignore
      applyTransaction(oldTimeoutTx, producer.pubkeyHash, stopProcessTxTime, blockTime, forkHead.block.height + 1)
    }

    val badTxs = ArrayBuffer.empty[Transaction]

    unapplyTxs.foreach(p => {
      if (Instant.now.toEpochMilli < stopProcessTxTime) {
        if (applyTransaction(p._2, producer.pubkeyHash, stopProcessTxTime, blockTime, forkHead.block.height + 1))
          pendingState.txs.append(p._2)
        else
          badTxs.append(p._2)
      }
    })
    pendingState.txs.foreach(tx => unapplyTxs.remove(tx.id))
    badTxs.foreach(tx => unapplyTxs.remove(tx.id))

    //    for (p <- unapplyTxs if Instant.now.toEpochMilli < stopProcessTxTime) {
    //      if (applyTransaction(p._2, producer.pubkey.pubKeyHash, stopProcessTxTime))
    //        pendingState.txs.append(p._2)
    //      unapplyTxs.remove(p._1)
    //    }
  }

  override def isProducingBlock(): Boolean = {
    pendingState.isProducingBlock
  }

  private def addTransactionToUnapplyTxs(tx: Transaction): Boolean = {
    if (!unapplyTxs.contains(tx.id)) {
      unapplyTxs += (tx.id -> tx)
    }
    true // always true
  }

  override def addTransaction(tx: Transaction): Boolean = {
    var added = false
    if (tx.gasLimit > runtimeParas.txAcceptGasLimit) {
      added = false
    }
    else if (isProducingBlock()) {
      if (Instant.now.toEpochMilli > pendingState.stopProcessTxTime) {
        added = addTransactionToUnapplyTxs(tx)
      }
      else {
        if (applyTransaction(tx, pendingState.producer.pubkeyHash, pendingState.stopProcessTxTime, pendingState.blockTime, pendingState.blockIndex)) {
          pendingState.txs.append(tx)
          added = true
        }
      }
    }
    else {
      added = addTransactionToUnapplyTxs(tx)
    }
    if (added)
      notification.broadcast(AddTransactionNotify(tx))
    added
  }

  override def produceBlockFinalize(): Option[Block] = {
    val endTime = Instant.now.toEpochMilli
    if (!isProducingBlock()) {
      log.info("block canceled")
      None
    } else {
      log.debug(s"block time: ${pendingState.blockTime}, end time: $endTime, produce time: ${endTime - pendingState.startTime}")
      val forkHead = forkBase.head.get
      val merkleRoot = MerkleTree.root(pendingState.txs.map(_.id))
      val privateKey = pendingState.producer.privkey.get
      val timeStamp = pendingState.blockTime
      val header = BlockHeader.build(
        forkHead.block.height + 1, timeStamp, merkleRoot,
        forkHead.block.id, privateKey)
      val block = Block.build(header, pendingState.txs.clone)
      pendingState.txs.clear()
      pendingState.isProducingBlock = false
      if (tryInsertBlock(block, false)) {
        log.info(s"block #${block.height} ${block.shortId} produced by ${block.header.producer.address.substring(0, 7)} ${block.header.timeString()}")
        notification.broadcast(NewBlockProducedNotify(block))
        Some(block)
      } else {
        None
      }
    }
  }

  private def stopProduceBlock() = {
    pendingState.txs.foreach(tx => {
      if (tx.txType != TransactionType.Miner)
        unapplyTxs += (tx.id -> tx)
    })
    pendingState.txs.clear()
    pendingState.isProducingBlock = false
    dataBase.rollBack()
  }

  override def tryInsertBlock(block: Block, doApply: Boolean): Boolean = {
    var inserted = false
    if (isProducingBlock())
      stopProduceBlock()

    if (forkBase.head.get.block.id.equals(block.prev())) {
      if (doApply == false) { // check first !
        require(forkBase.add(block))
        inserted = true
      }
      else if (applyBlock(block)) {
        require(forkBase.add(block))
        inserted = true
      }
      else
        log.info(s"block ${block.height} ${block.shortId} apply error")
      if (inserted) {
        dataBase.commit()
        notification.broadcast(BlockAddedToHeadNotify(block))
      }
    }
    else {
      log.info(s"try add received block to minor fork chain. block ${block.height} ${block.shortId}")
      if (forkBase.add(block))
        inserted = true
      else
        log.debug("fail add to minor fork chain")
    }
    if (inserted) {
      block.transactions.foreach(tx => {
        unapplyTxs.remove(tx.id)
        if (timeoutTx.isDefined && timeoutTx.get.id() == tx.id()) {
          timeoutTx = None
        }
      })
    }
    inserted
  }

  private def applyBlock(block: Block, verify: Boolean = true, enableSession: Boolean = true): Boolean = {
    var applied = true
    //    if (isPendingBlock) {
    //      rollBack()
    //    }
    if (!verify || verifyBlock(block)) {
      if (enableSession)
        dataBase.startSession()
      block.transactions.foreach(tx => {
        if (applied && !applyTransaction(tx, block.header.producer, Long.MaxValue, block.header.timeStamp, block.height()))
          applied = false
      })
      if (enableSession && !applied)
        dataBase.rollBack()
    }
    else
      applied = false
    if (!applied) {
      log.error(s"Block apply fail #${block.height()} ${block.shortId}")
    }
    applied
  }

  private def applyTransaction(tx: Transaction, blockProducer: UInt160,
                               stopTime: Long, timeStamp: Long, blockIndex: Long): Boolean = {
    var txValid = false
    tx.txType match {
      case TransactionType.Miner => txValid = applySendTransaction(tx, blockProducer)
      case TransactionType.Transfer => txValid = applySendTransaction(tx, blockProducer)
      //case TransactionType.Fee =>
      //case TransactionType.RegisterName =>
      case TransactionType.Deploy => txValid = applyContractTransaction(tx, blockProducer, stopTime, timeStamp, blockIndex)
      case TransactionType.Call => txValid = applyContractTransaction(tx, blockProducer, stopTime, timeStamp, blockIndex)
    }
    txValid
  }

  private def applyContractTransaction(tx: Transaction, blockProducer: UInt160,
                                       stopTime: Long, timeStamp: Long, blockIndex: Long): Boolean = {

    var applied = false

    val executor = new TransactionExecutor(tx, blockProducer, dataBase, stopTime, timeStamp, blockIndex)

    executor.init()
    executor.execute()
    executor.go()

    val summary = executor.finalization()
    val receipt = executor.getReceipt

    if (executor.getResult.isBlockTimeout) {
      log.error(s"tx ${tx.id.shortString()} executor time out")
      if (isProducingBlock())
        timeoutTx = Some(tx)
      applied = false
    }
    else if (executor.getResult.isRunOutOfGas) {
      applied = true
    }
    else if (!receipt.isSuccessful()) {
      log.error(s"tx ${tx.id().shortString()} execute error: ${receipt.error}")
      applied = false
    }
    else {
      applied = true
    }
    dataBase.setReceipt(tx.id(), receipt)
    applied
  }

  private def applySendTransaction(tx: Transaction, blockProducer: UInt160): Boolean = {
    var txValid = true

    val fromAccount = dataBase.getAccount(tx.from).getOrElse(Account.newAccount(tx.from))
    val toAccount = dataBase.getAccount(tx.toPubKeyHash).getOrElse(Account.newAccount(tx.toPubKeyHash))

    var txFee = FixedNumber.Zero
    val txGas = tx.transactionCost()

    if (tx.txType == TransactionType.Miner) {

    }
    else {
      txFee = FixedNumber(BigInt(txGas)) * tx.gasPrice
      if (txGas > tx.gasLimit) {
        log.info(s"Not enough gas for transaction tx ${tx.id().shortString()}")
        txValid = false
      }
      if ((tx.amount + txFee) > fromAccount.balance) {
        log.info(s"Not enough balance for transaction tx ${tx.id().shortString()}")
        txValid = false
      }
      else if (tx.nonce != fromAccount.nextNonce) {
        log.info(s"tx ${tx.id().shortString()} nonce ${tx.nonce} invalid, expect ${fromAccount.nextNonce}")
        txValid = false
      }
    }

    if (txValid) {
      dataBase.transfer(tx.from, tx.toPubKeyHash, tx.amount)
      dataBase.increaseNonce(tx.from)

      if (txFee.value > 0) {
        dataBase.transfer(tx.from, blockProducer, txFee)
      }

      dataBase.setReceipt(tx.id(), TransactionReceipt(tx.id(), tx.txType, tx.from, tx.toPubKeyHash, txGas, BinaryData.empty, 0, ""))

    }
    txValid
  }

  private def verifyBlock(block: Block): Boolean = {
    if (!verifyHeader(block.header))
      false
    else if (block.transactions.size == 0) {
      log.info("verifyBlock error: block.transactions.size == 0")
      false
    }
    else if (!block.merkleRoot().equals(block.header.merkleRoot))
      false
    else if (!verifyTxTypeAndSignature(block.transactions))
      false
    else if (!verifyRegisterNames(block.transactions))
      false
    else
      true
  }

  private def verifyTxTypeAndSignature(txs: Seq[Transaction]): Boolean = {
    var isValid = true
    var minerTxNum = 0
    txs.foreach(tx => {
      if (tx.txType == TransactionType.Miner) {
        minerTxNum += 1
        if (tx.amount.value != minerAward.value)
          isValid = false
      }
      else if (!tx.verifySignature())
        isValid = false
    })
    if (minerTxNum > 1)
      isValid = false
    isValid
  }

  private def verifyHeader(header: BlockHeader): Boolean = {
    val prevBlock = forkBase.get(header.prevBlock)
    val now = Instant.now.toEpochMilli
    if (prevBlock.isEmpty) {
      log.error("verifyHeader error: prevBlock not found")
      false
    }
    else if (header.timeStamp <= prevBlock.get.block.header.timeStamp) {
      log.error(s"verifyHeader error: timeStamp not valid  ${header.timeStamp}  ${prevBlock.get.block.header.timeStamp}")
      false
    }
    else if (header.timeStamp - now > 2000) {
      log.error(s"verifyHeader error: timeStamp too far in future. now=$now timeStamp=${header.timeStamp}")
      false
    }
    else if (header.index != prevBlock.get.block.height() + 1) {
      log.error(s"verifyHeader error: index error ${header.index} ${prevBlock.get.block.height()}")
      false
    }
    else if (!ProducerUtil.isProducerValid(header.timeStamp, header.producer, consensusSettings)) {
      log.error("verifyHeader error: producer not valid")
      false
    }
    else if (!header.verifySig()) {
      log.error("verifyHeader error: verifySig fail")
      false
    }
    else {
      // verify merkleRoot in verifyBlock()
      true
    }
  }

  private def verifyRegisterNames(transactions: Seq[Transaction]): Boolean = {
    var isValid = true
    val newNames = Set.empty[String]
    val registers = Set.empty[UInt160]
    transactions.foreach(tx => {
      if (tx.txType == TransactionType.RegisterName) {
        val name = new String(tx.data, "UTF-8")
        if (name.length != 10) // TODO: read "10" from config file
          isValid = false
        if (newNames.contains(name))
          isValid = false
        if (registers.contains(tx.from))
          isValid = false
        newNames.add(name)
        registers.add(tx.from)
      }
    })

    isValid = !newNames.exists(dataBase.nameExists)
    isValid = !registers.exists(dataBase.accountExists)
    isValid
  }

  override def getBalance(address: UInt160): Option[FixedNumber] = {
    dataBase.getBalance(address)
  }

  override def getAccount(address: UInt160): Option[Account] = {
    dataBase.getAccount(address)
  }

  def getReceipt(txid: UInt256): Option[TransactionReceipt] = {
    dataBase.getReceipt(txid)
  }

  private def populate(): Unit = {
    log.info("chain populate")
    if (forkBase.head.isEmpty) {
      applyBlock(genesisBlock, false, false)
      blockBase.add(genesisBlock)
      forkBase.add(genesisBlock)
      notification.broadcast(BlockAddedToHeadNotify(genesisBlock))
    }

    require(forkBase.head.isDefined)

    forkBase.switchState.foreach(resolveSwitchFailure)

    forkBase.head.foreach(resolveDbUnConsistent)

    require(forkBase.head.map(_.block.height).get >= blockBase.head.map(_.index).get)

    val latestHeader = forkBase.head.get.block.header

    log.info(s"populate() latest block ${latestHeader.index} ${latestHeader.shortId()}")
  }

  private def resolveSwitchFailure(state: SwitchState): Unit = {
    val oldBranch = forkBase.getBranch(state.oldHead, state.forkPoint)
    val newBranch = forkBase.getBranch(state.newHead, state.forkPoint)
    val result = onSwitch(oldBranch, newBranch, state)
    forkBase.endSwitch(oldBranch, newBranch, result)
  }

  private def resolveDbUnConsistent(head: ForkItem): Unit = {
    while (dataBase.revision > head.height + 1) {
      dataBase.rollBack()
    }
  }

  private def onConfirmed(block: Block): Unit = {
    if (block.height > 0) {
      log.info(s"confirm block ${block.height} (${block.shortId})")
      dataBase.commit(block.height)
      blockBase.add(block)
    }
    notification.broadcast(BlockConfirmedNotify(block))
  }

  private def onSwitch(from: Seq[ForkItem], to: Seq[ForkItem], switchState: SwitchState): SwitchResult = {
    def printChain(title: String, fork: Seq[ForkItem]): Unit = {
      log.info(s"$title: ${fork.map(_.block.shortId).mkString(" <- ")}")
    }

    printChain("old chain", from)
    printChain("new chain", to)

    require(dataBase.revision == from.last.height + 1)
    while (dataBase.revision > switchState.height + 1) {
      dataBase.rollBack()
    }

    var appliedCount = 0
    for (item <- to if applyBlock(item.block)) {
      appliedCount += 1
    }

    if (appliedCount < to.size) {
      while (dataBase.revision > switchState.height + 1) {
        dataBase.rollBack()
      }
      from.foreach(item => applyBlock(item.block))
      SwitchResult(false, to(appliedCount))
    } else {
      notification.broadcast(ForkSwitchNotify(from, to))
      SwitchResult(true)
    }
  }

  def setGasLimit(gasLimit: BigInt): Boolean = {
    try {
      peerBase.setGasLimit(gasLimit)
      runtimeParas.setAcceptGasLimit(gasLimit.longValue())
      true
    }catch {
      case e: Throwable =>false
    }
  }
  def getGasLimit(): Long = {
    peerBase.getGasLimit().get.longValue()
  }
}


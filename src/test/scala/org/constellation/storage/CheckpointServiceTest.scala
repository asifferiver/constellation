package org.constellation.storage

import java.security.KeyPair

import better.files.File
import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.typesafe.scalalogging.Logger
import constellation.createTransaction
import org.constellation.consensus.FinishedCheckpoint
import org.constellation.crypto.KeyUtils.makeKeyPair
import org.constellation.primitives.Schema._
import org.constellation.primitives._
import org.constellation.storage.transactions.TransactionStatus
import org.constellation.util.{APIClient, HostPort, Metrics}
import org.constellation.{ConstellationContextShift, DAO, Fixtures, PeerMetadata}
import org.mockito.Mockito.doNothing
import org.mockito.{ArgumentMatchersSugar, IdiomaticMockito}
import org.scalatest.{BeforeAndAfter, FreeSpec, FunSuite, Matchers}

class CheckpointServiceTest
    extends FreeSpec
    with IdiomaticMockito
    with ArgumentMatchersSugar
    with Matchers
    with BeforeAndAfter {

  val readyFacilitators: Map[Schema.Id, PeerData] = prepareFacilitators()
  val soe: SignedObservationEdge = mock[SignedObservationEdge]

  implicit val kp: KeyPair = makeKeyPair()
  implicit var dao: DAO = _

  before {
    soe.baseHash shouldReturn "abc"
  }

  "with mocked dao" - {
    dao = preparMockedDao()

    "should convert CB to merkle roots when all data is filled" in {

      val cbProposal = CheckpointBlock
        .createCheckpointBlockSOE(prepareTransactions(), Seq(soe), prepareMessages(), prepareNotifications())

      val cbProposalCache = CheckpointCache(Some(cbProposal), 3, Some(Height(2, 4)))
      dao.checkpointService.memPool.put(cbProposal.baseHash, cbProposalCache).unsafeRunSync()

      val storedCB = dao.checkpointService.memPool.lookup(cbProposal.baseHash).unsafeRunSync().get

      dao.checkpointService.convert(storedCB).unsafeRunSync() shouldBe cbProposalCache
    }

    "should convert CB to merkle roots when minimum data is filled" in {
      val fullData = storeCheckpointBlock(prepareTransactions(), Seq.empty, Seq.empty)
      val storedCB = dao.checkpointService.memPool.lookup(fullData.checkpointBlock.get.baseHash).unsafeRunSync().get

      dao.checkpointService.convert(storedCB).unsafeRunSync() shouldBe fullData
    }

    "should fetch messages when they exist" in {
      val msgs = prepareMessages()
      val fullData = storeCheckpointBlock(prepareTransactions(), msgs, Seq.empty)
      val storedCB = dao.checkpointService.memPool.lookup(fullData.checkpointBlock.get.baseHash).unsafeRunSync().get

      dao.checkpointService
        .fetchMessages(storedCB.checkpointBlock.messagesMerkleRoot.get)
        .unsafeRunSync() shouldBe msgs
    }

    "should fetch transactions when they exist" in {
      val txs = prepareTransactions()
      val fullData = storeCheckpointBlock(txs, Seq.empty, Seq.empty)
      val storedCB = dao.checkpointService.memPool.lookup(fullData.checkpointBlock.get.baseHash).unsafeRunSync().get

      dao.checkpointService
        .fetchTransactions(storedCB.checkpointBlock.transactionsMerkleRoot.get)
        .unsafeRunSync() shouldBe txs
    }

    "should fetch notifications when they exist" in {
      val notifications = prepareNotifications()
      val fullData = storeCheckpointBlock(prepareTransactions(), Seq.empty, notifications)
      val storedCB = dao.checkpointService.memPool.lookup(fullData.checkpointBlock.get.baseHash).unsafeRunSync().get

      dao.checkpointService
        .fetchNotifications(storedCB.checkpointBlock.notificationsMerkleRoot.get)
        .unsafeRunSync() shouldBe notifications
    }
  }

  "with real dao" - {
    dao = prepareRealDao()

    "should accept cb resolving parents soeHashes and cb baseHashes recursively" in {
      val go = Genesis.createGenesisAndInitialDistributionDirect("selfAddress", Set(dao.id), dao.keyPair)
      Genesis.acceptGenesis(go, setAsTips = true)

      val startingTips: Seq[SignedObservationEdge] = Seq(go.initialDistribution.soe, go.initialDistribution2.soe)

      val cb1 = makeBlock(startingTips)

      val cb2 = makeBlock(startingTips)
      val cb3 = makeBlock(Seq(cb1.soe, cb2.soe))

      val peer = readyFacilitators(Id("b")).client
      val blocks = Seq(cb1, cb2)

      blocks.foreach { c =>
        peer.getNonBlockingIO[Option[SignedObservationEdgeCache]](eqTo(s"soe/${c.soeHash}"), *, *)(*, *) shouldReturn IO
          .pure(Some(SignedObservationEdgeCache(c.soe)))

        peer.getNonBlockingIO[Option[CheckpointCache]](eqTo(s"checkpoint/${c.baseHash}"), *, *)(*, *) shouldReturn IO
          .pure(Some(CheckpointCache(Some(c))))
      }

      dao.checkpointService
        .accept(FinishedCheckpoint(CheckpointCache(Some(cb3), 0, Some(Height(1, 1))), Set(dao.id)))
        .unsafeRunSync()
      dao.checkpointService.contains(cb3.baseHash).unsafeRunSync() shouldBe true
    }
  }

  private def makeBlock(tips: Seq[SignedObservationEdge]): CheckpointBlock =
    CheckpointBlock.createCheckpointBlock(
      Seq(createTransaction(dao.selfAddressStr, Fixtures.id2.address, 75L, dao.keyPair)),
      tips.map { s =>
        TypedEdgeHash(s.hash, EdgeHashType.CheckpointHash)
      },
      Seq(),
      Seq()
    )

  private def storeCheckpointBlock(
    txs: Seq[Transaction],
    msgs: Seq[ChannelMessage],
    notifics: Seq[PeerNotification]
  ): CheckpointCache = {

    val cbProposal = CheckpointBlock.createCheckpointBlockSOE(txs, Seq(soe), msgs, notifics)

    val cbProposalCache = CheckpointCache(Some(cbProposal), 3, Some(Height(2, 4)))
    dao.checkpointService.memPool.put(cbProposal.baseHash, cbProposalCache).unsafeRunSync()
    cbProposalCache
  }

  private def prepareTransactions(): Seq[Transaction] = {
    val tx1 = mock[Transaction]
    tx1.hash shouldReturn "tx1"
    val tx2 = mock[Transaction]
    tx2.hash shouldReturn "tx2"

    (dao.transactionService.put(TransactionCacheData(tx1), TransactionStatus.Accepted) *>
      dao.transactionService.put(TransactionCacheData(tx2), TransactionStatus.Accepted))
      .unsafeRunSync()

    Seq(tx1, tx2)
  }

  private def prepareNotifications(): Seq[PeerNotification] = {
    val notification1 = mock[PeerNotification]
    notification1.hash shouldReturn "notification1"

    val notification2 = mock[PeerNotification]
    notification2.hash shouldReturn "notification2"

    (dao.notificationService.memPool.put(notification1.hash, notification1) *>
      dao.notificationService.memPool.put(notification2.hash, notification2))
      .unsafeRunSync()

    Seq(notification1, notification2)
  }

  private def prepareMessages(): Seq[ChannelMessage] = {
    val msg1 = mock[ChannelMessage]
    msg1.signedMessageData shouldReturn mock[SignedData[ChannelMessageData]]
    msg1.signedMessageData.hash shouldReturn "msg1"

    val msg2 = mock[ChannelMessage]
    msg2.signedMessageData shouldReturn mock[SignedData[ChannelMessageData]]
    msg2.signedMessageData.hash shouldReturn "msg2"

    (dao.messageService.memPool.put(msg1.signedMessageData.hash, ChannelMessageMetadata(msg1)) *>
      dao.messageService.memPool.put(msg2.signedMessageData.hash, ChannelMessageMetadata(msg2)))
      .unsafeRunSync()
    Seq(msg1, msg2)
  }

  private def preparMockedDao(): DAO = {
    import constellation._

    val dao: DAO = mock[DAO]

    implicit val contextShift: ContextShift[IO] = ConstellationContextShift.global

    val f = File(s"tmp/${kp.getPublic.toId.medium}/db")
    f.createDirectoryIfNotExists()
    dao.dbPath shouldReturn f

    dao.id shouldReturn Fixtures.id

    val ss = new SOEService[IO]()
    dao.soeService shouldReturn ss

    val ns = new NotificationService[IO]()
    dao.notificationService shouldReturn ns

    val ms = {
      implicit val shadedDao = dao
      new MessageService[IO]()
    }
    dao.messageService shouldReturn ms

    val ts = new TransactionService[IO](dao)
    dao.transactionService shouldReturn ts

    val cts = mock[ConcurrentTipService]

    val rl = mock[RateLimiting[IO]]
    val cs = new CheckpointService[IO](dao, ts, ms, ns, cts, rl)
    dao.checkpointService shouldReturn cs

    val metrics = mock[Metrics]
    doNothing().when(metrics).incrementMetric(*)
    dao.metrics shouldReturn metrics

    dao.nodeState shouldReturn NodeState.Ready

    dao.miscLogger shouldReturn Logger("miscLogger")

    dao.readyPeers shouldReturn IO.pure(readyFacilitators)

    dao
  }

  private def prepareRealDao(): DAO = {
    val dao: DAO = new DAO {
      override def readyPeers: IO[
        Map[Id, PeerData]
      ] = IO.pure(readyFacilitators)
    }
    dao.initialize()
    dao.metrics = new Metrics()(dao)
    dao.nodeState = NodeState.Ready
    dao
  }

  private def prepareFacilitators(): Map[Schema.Id, PeerData] = {

    val facilitatorId1 = Schema.Id("b")
    val peerData1: PeerData = mock[PeerData]
    peerData1.peerMetadata shouldReturn mock[PeerMetadata]
    peerData1.peerMetadata.id shouldReturn facilitatorId1
    peerData1.notification shouldReturn Seq()
    peerData1.client shouldReturn mock[APIClient]
    peerData1.client.hostPortForLogging shouldReturn HostPort("http://b", 9000)

    Map(facilitatorId1 -> peerData1)
  }
}

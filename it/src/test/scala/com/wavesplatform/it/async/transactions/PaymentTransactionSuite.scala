package com.wavesplatform.it.async.transactions

import com.wavesplatform.it.api.AsyncHttpApi._
import com.wavesplatform.it.api.PaymentRequest
import com.wavesplatform.it.transactions.BaseTransactionSuite
import com.wavesplatform.it.util._

import scala.concurrent.Await
import scala.concurrent.duration._

class PaymentTransactionSuite extends BaseTransactionSuite {


  private val paymentAmount = 5.Agate
  private val defaulFee     = 1.Agate

  test("Agate payment changes Agate balances and eff.b.") {
    val f = for {
      ((firstBalance, firstEffBalance), (secondBalance, secondEffBalance)) <- notMiner
        .accountBalances(firstAddress)
        .zip(notMiner.accountBalances(secondAddress))

      transferId <- sender.payment(firstAddress, secondAddress, paymentAmount, defaulFee).map(_.id)
      _          <- nodes.waitForHeightAriseAndTxPresent(transferId)
      _ <- notMiner
        .assertBalances(firstAddress, firstBalance - paymentAmount - defaulFee, firstEffBalance - paymentAmount - defaulFee)
        .zip(notMiner.assertBalances(secondAddress, secondBalance + paymentAmount, secondEffBalance + paymentAmount))
    } yield succeed

    Await.result(f, 2.minute)
  }

  test("obsolete endpoints respond with BadRequest") {
    val payment      = PaymentRequest(5.Agate, 1.Agate, firstAddress, secondAddress)
    val errorMessage = "This API is no longer supported"
    val f = for {
      _ <- assertBadRequestAndMessage(sender.postJson("/Agate/payment/signature", payment), errorMessage)
      _ <- assertBadRequestAndMessage(sender.postJson("/Agate/create-signed-payment", payment), errorMessage)
      _ <- assertBadRequestAndMessage(sender.postJson("/Agate/external-payment", payment), errorMessage)
      _ <- assertBadRequestAndMessage(sender.postJson("/Agate/broadcast-signed-payment", payment), errorMessage)
    } yield succeed

    Await.result(f, 1.minute)
  }
}

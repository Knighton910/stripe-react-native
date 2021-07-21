package com.reactnativestripesdk

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import com.stripe.android.googlepaylauncher.GooglePayEnvironment
import com.stripe.android.googlepaylauncher.GooglePayLauncher
import com.stripe.android.googlepaylauncher.GooglePayPaymentMethodLauncher
import com.stripe.android.model.StripeIntent

class GooglePayFragment : Fragment() {
  private var googlePayLauncher: GooglePayLauncher? = null
  private var googlePayMethodLauncher: GooglePayPaymentMethodLauncher? = null

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return FrameLayout(requireActivity()).also {
      it.visibility = View.GONE
    }
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val testEnv = arguments?.getBoolean("testEnv")
    val createPaymentMethod = arguments?.getBoolean("createPaymentMethod")
    val merchantName = arguments?.getString("merchantName").orEmpty()
    val countryCode = arguments?.getString("countryCode").orEmpty()
    val isRequired = arguments?.getBoolean("isRequired") ?: false
    val formatString = arguments?.getString("format").orEmpty()
    val isPhoneNumberRequired = arguments?.getBoolean("isPhoneNumberRequired") ?: false
    val isEmailRequired = arguments?.getBoolean("isEmailRequired") ?: false
    val existingPaymentMethodRequired = arguments?.getBoolean("existingPaymentMethodRequired") ?: false

    if (createPaymentMethod == true) {
      val billingAddressConfig = mapToGooglePayPaymentMethodLauncherBillingAddressConfig(formatString, isRequired, isPhoneNumberRequired)

      googlePayMethodLauncher = GooglePayPaymentMethodLauncher(
        fragment = this,
        config = GooglePayPaymentMethodLauncher.Config(
          environment = if (testEnv == true) GooglePayEnvironment.Test else GooglePayEnvironment.Production,
          merchantCountryCode = countryCode,
          merchantName = merchantName,
          billingAddressConfig = billingAddressConfig,
          isEmailRequired = isEmailRequired,
          existingPaymentMethodRequired = existingPaymentMethodRequired
        ),
        readyCallback = ::onGooglePayReady,
        resultCallback = ::onGooglePayResult
      )
    } else {
      val billingAddressConfig = mapToGooglePayLauncherBillingAddressConfig(formatString, isRequired, isPhoneNumberRequired)

      googlePayLauncher = GooglePayLauncher(
        fragment = this,
        config = GooglePayLauncher.Config(
          environment = if (testEnv == true) GooglePayEnvironment.Test else GooglePayEnvironment.Production,
          merchantCountryCode = countryCode,
          merchantName = merchantName,
          billingAddressConfig = billingAddressConfig,
          isEmailRequired = isEmailRequired,
          existingPaymentMethodRequired = existingPaymentMethodRequired
        ),
        readyCallback = ::onGooglePayReady,
        resultCallback = ::onGooglePayResult
      )
    }

    val intent = Intent(ON_GOOGLE_PAY_FRAGMENT_CREATED)
    activity?.sendBroadcast(intent)
  }

  fun presentForPaymentIntent(clientSecret: String) {
    if (googlePayLauncher == null) {
      val intent = Intent(ON_GOOGLE_PAY_RESULT)
      intent.putExtra("error", "GooglePayLauncher is not initialized. Please make sure that createPaymentMethod option is set to false")
      activity?.sendBroadcast(intent)
      return
    }
    googlePayLauncher?.presentForPaymentIntent(clientSecret)
  }

  fun presentForSetupIntent(clientSecret: String, currencyCode: String) {
    if (googlePayLauncher == null) {
      val intent = Intent(ON_GOOGLE_PAY_RESULT)
      intent.putExtra("error", "GooglePayLauncher is not initialized. Please make sure that createPaymentMethod option is set to false")
      activity?.sendBroadcast(intent)
      return
    }
    googlePayLauncher?.presentForSetupIntent(clientSecret, currencyCode)
  }

  fun createPaymentMethod(currencyCode: String, amount: Int) {
    if (googlePayMethodLauncher == null) {
      val intent = Intent(ON_GOOGLE_PAYMENT_METHOD_RESULT)
      intent.putExtra("error", "GooglePayPaymentMethodLauncher is not initialized. Please make sure that createPaymentMethod option is set to true")
      activity?.sendBroadcast(intent)
      return
    }
    googlePayMethodLauncher?.present(
      currencyCode = currencyCode,
      amount = amount
    )
  }

  private fun onGooglePayReady(isReady: Boolean) {
    val intent = Intent(ON_INIT_GOOGLE_PAY)
    intent.putExtra("isReady", isReady)
    activity?.sendBroadcast(intent)
  }

  private fun onGooglePayResult(result: GooglePayLauncher.Result) {
    val intent = Intent(ON_GOOGLE_PAY_RESULT)
    intent.putExtra("paymentResult", result)
    activity?.sendBroadcast(intent)
  }

  private fun onGooglePayResult(result: GooglePayPaymentMethodLauncher.Result) {
    val intent = Intent(ON_GOOGLE_PAYMENT_METHOD_RESULT)
    intent.putExtra("paymentResult", result)
    activity?.sendBroadcast(intent)
  }

  private fun mapToGooglePayLauncherBillingAddressConfig(formatString: String, isRequired: Boolean, isPhoneNumberRequired: Boolean): GooglePayLauncher.BillingAddressConfig {
    val format = when (formatString) {
      "FULL" -> GooglePayLauncher.BillingAddressConfig.Format.Full
      "MIN" -> GooglePayLauncher.BillingAddressConfig.Format.Min
      else -> GooglePayLauncher.BillingAddressConfig.Format.Min
    }
    return GooglePayLauncher.BillingAddressConfig(
      isRequired = isRequired,
      format = format,
      isPhoneNumberRequired = isPhoneNumberRequired
    )
  }

  private fun mapToGooglePayPaymentMethodLauncherBillingAddressConfig(formatString: String, isRequired: Boolean, isPhoneNumberRequired: Boolean): GooglePayPaymentMethodLauncher.BillingAddressConfig {
    val format = when (formatString) {
      "FULL" -> GooglePayPaymentMethodLauncher.BillingAddressConfig.Format.Full
      "MIN" -> GooglePayPaymentMethodLauncher.BillingAddressConfig.Format.Min
      else -> GooglePayPaymentMethodLauncher.BillingAddressConfig.Format.Min
    }
    return GooglePayPaymentMethodLauncher.BillingAddressConfig(
      isRequired = isRequired,
      format = format,
      isPhoneNumberRequired = isPhoneNumberRequired
    )
  }
}
const express = require("express");
const app = express();
const { resolve } = require("path");
// Replace if using a different env file or config
const envPath = resolve(__dirname, "../../../.env");
const env = require("dotenv").config({ path: envPath });
const stripe = require("stripe")(env.parsed.STRIPE_SECRET_KEY);

app.use(express.static(env.parsed.STATIC_DIR));
app.use(
  express.json({
    // We need the raw body to verify webhook signatures.
    // Let's compute it only when hitting the Stripe webhook endpoint.
    verify: function(req, res, buf) {
      if (req.originalUrl.startsWith("/webhook")) {
        req.rawBody = buf.toString();
      }
    }
  })
);

app.get("/", (req, res) => {
  // Display checkout page
  const path = resolve(env.parsed.STATIC_DIR + "/index.html");
  res.sendFile(path);
});

const calculateOrderAmount = items => {
  // Replace this constant with a calculation of the order's amount
  // Calculate the order total on the server to prevent
  // people from directly manipulating the amount on the client
  return 1400;
};

app.post("/create-payment-intent", async (req, res) => {
  const { items, currency } = req.body;

  // Create a PaymentIntent with the order amount and currency
  const paymentIntent = await stripe.paymentIntents.create({
    amount: calculateOrderAmount(items),
    currency: currency
  });

  // Send public key and PaymentIntent details to client
  res.send({
    publicKey: env.parsed.STRIPE_PUBLIC_KEY,
    clientSecret: paymentIntent.client_secret,
    id: paymentIntent.id
  });
});

// Webhook handler for asynchronous events.
app.post("/webhook", async (req, res) => {
  // Check if webhook signing is configured.
  if (env.parsed.STRIPE_WEBHOOK_SECRET) {
    // Retrieve the event by verifying the signature using the raw body and secret.
    let event;
    let signature = req.headers["stripe-signature"];
    try {
      event = stripe.webhooks.constructEvent(
        req.rawBody,
        signature,
        env.parsed.STRIPE_WEBHOOK_SECRET
      );
    } catch (err) {
      console.log(`⚠️  Webhook signature verification failed.`);
      return res.sendStatus(400);
    }
    data = event.data;
    eventType = event.type;
  } else {
    // Webhook signing is recommended, but if the secret is not configured in `config.js`,
    // we can retrieve the event data directly from the request body.
    data = req.body.data;
    eventType = req.body.type;
  }

  if (eventType === "payment_method.attached") {
    // The PaymentMethod is attached with the client call to handleCardPayment
    console.log("❗ PaymentMethod successfully attached to Customer");
  } else if (eventType === "payment_intent.succeeded") {
    if (data.object.setup_future_usage !== null) {
      // You need a Customer to save a card
      // Create or use a preexisting Customer
      const customer = await stripe.customers.create({
        payment_method: data.object.payment_method
      });
    } else {
      console.log("❗ Customer did not want to save the card. ");
    }

    // Funds have been captured
    // Fulfill any orders, e-mail receipts, etc
    // To cancel the payment after capture you will need to issue a Refund (https://stripe.com/docs/api/refunds)
    console.log("💰 Payment captured!");
  } else if (eventType === "payment_intent.payment_failed") {
    console.log("❌ Payment failed.");
  }
  res.sendStatus(200);
});

app.listen(4242, () => console.log(`Node server listening on port ${4242}!`));

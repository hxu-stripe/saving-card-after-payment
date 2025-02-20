package com.stripe.sample;

import java.nio.file.Paths;

import static spark.Spark.post;
import static spark.Spark.staticFiles;
import static spark.Spark.port;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import com.stripe.Stripe;
import com.stripe.net.ApiResource;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.exception.*;
import com.stripe.net.Webhook;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.CustomerCreateParams;
import com.stripe.model.Customer;

import io.github.cdimascio.dotenv.Dotenv;

public class Server {
    private static Gson gson = new Gson();

    static class CreatePaymentBody {
        @SerializedName("items")
        Object[] items;

        @SerializedName("currency")
        String currency;

        public Object[] getItems() {
            return items;
        }

        public String getCurrency() {
            return currency;
        }
    }

    static class CreatePaymentResponse {
        private String publicKey;
        private String clientSecret;
        private String id;

        public CreatePaymentResponse(String publicKey, String clientSecret, String id) {
            this.publicKey = publicKey;
            this.clientSecret = clientSecret;
            this.id = id;
        }
    }

    static int calculateOrderAmount(Object[] items) {
        // Replace this constant with a calculation of the order's amount
        // Calculate the order total on the server to prevent
        // users from directly manipulating the amount on the client
        return 1400;
    }

    public static void main(String[] args) {
        port(4242);
        String ENV_PATH = "../../../";
        Dotenv dotenv = Dotenv.configure().directory(ENV_PATH).load();

        Stripe.apiKey = dotenv.get("STRIPE_SECRET_KEY");

        staticFiles.externalLocation(
                Paths.get(Paths.get("").toAbsolutePath().toString(), dotenv.get("STATIC_DIR")).normalize().toString());

        post("/create-payment-intent", (request, response) -> {
            response.type("application/json");

            CreatePaymentBody postBody = gson.fromJson(request.body(), CreatePaymentBody.class);
            PaymentIntentCreateParams createParams = new PaymentIntentCreateParams.Builder()
                    .setCurrency(postBody.getCurrency()).setAmount(new Long(calculateOrderAmount(postBody.getItems())))
                    .build();
            // Create a PaymentIntent with the order amount and currency
            PaymentIntent intent = PaymentIntent.create(createParams);
            // Send public key and PaymentIntent details to client
            return gson.toJson(new CreatePaymentResponse(dotenv.get("STRIPE_PUBLIC_KEY"), intent.getClientSecret(),
                    intent.getId()));
        });

        post("/webhook", (request, response) -> {
            String payload = request.body();
            String sigHeader = request.headers("Stripe-Signature");
            String endpointSecret = dotenv.get("STRIPE_WEBHOOK_SECRET");

            Event event = null;

            try {
                event = Webhook.constructEvent(payload, sigHeader, endpointSecret);
            } catch (SignatureVerificationException e) {
                // Invalid signature
                response.status(400);
                return "";
            }

            EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();

            switch (event.getType()) {
            case "payment_method.attached":
                // The PaymentMethod is attached with the client call to handleCardPayment
                System.out.println("❗ PaymentMethod successfully attached to Customer");
                break;
            case "payment_intent.succeeded":
                PaymentIntent intent = ApiResource.GSON.fromJson(deserializer.getRawJson(), PaymentIntent.class);

                CustomerCreateParams customerCreateParams = new CustomerCreateParams.Builder()
                        .setPaymentMethod(intent.getPaymentMethod()).build();
                Customer customer = Customer.create(customerCreateParams);

                // Fulfill any orders, e-mail receipts, etc
                // To cancel the payment after capture you will need to issue a Refund
                // (https://stripe.com/docs/api/refunds)
                System.out.println("💰 Payment received!");
                break;
            case "payment_intent.payment_failed":
                System.out.println("❌ Payment failed.");
                break;
            default:
                // Unexpected event type
                response.status(400);
                return "";
            }

            response.status(200);
            return "";
        });
}}
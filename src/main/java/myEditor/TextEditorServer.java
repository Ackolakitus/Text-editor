package myEditor;

import com.rabbitmq.client.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class TextEditorServer {
    private static final String QUEUE_NAME = "text_changes_queue";
    private static final String EXCHANGE_NAME = "text_updates";
    private static final String CLIENTS_EXCHANGE = "clients_updates";
    private static final AtomicInteger clientCount = new AtomicInteger(0);

    public static void main(String[] args) throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        Connection connection = factory.newConnection();
        Channel channel = connection.createChannel();

        channel.queueDeclare(QUEUE_NAME, false, false, false, null);
        channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.FANOUT);
        channel.exchangeDeclare(CLIENTS_EXCHANGE, BuiltinExchangeType.FANOUT);

        System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

        DeliverCallback deliverCallback = (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            // Split the message into file path and content
            int delimiterIndex = message.indexOf("::");
            if (delimiterIndex != -1) {
                String filePath = message.substring(0, delimiterIndex);
                String fileContent = message.substring(delimiterIndex + 2);

                try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath, false))) {
                    writer.write(fileContent);
                    System.out.println(" [x] Received and wrote message to file: " + filePath);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Publish the message to the exchange for clients to update their text areas
                channel.basicPublish(EXCHANGE_NAME, "", null, message.getBytes(StandardCharsets.UTF_8));
            } else {
                System.err.println(" [x] Received malformed message: " + message);
            }
        };

        channel.basicConsume(QUEUE_NAME, true, deliverCallback, consumerTag -> { });

        connection.addShutdownListener(cause -> {
            int count = clientCount.decrementAndGet();
            updateClientCount(channel, count);
        });

        // Listen for client registration
        String registrationQueue = channel.queueDeclare().getQueue();
        channel.queueBind(registrationQueue, CLIENTS_EXCHANGE, "");
        channel.basicConsume(registrationQueue, true, (consumerTag, delivery) -> {
            String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
            if ("register".equals(message)) {
                int count = clientCount.incrementAndGet();
                updateClientCount(channel, count);
            } else if ("unregister".equals(message)) {
                int count = clientCount.decrementAndGet();
                updateClientCount(channel, count);
            }
        }, consumerTag -> { });
    }

    private static void updateClientCount(Channel channel, int count) {
        String countMessage = String.valueOf(count);
        try {
            channel.basicPublish(CLIENTS_EXCHANGE, "", null, countMessage.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

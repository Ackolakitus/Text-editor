package myEditorLineByLine;

import com.rabbitmq.client.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public class TextEditorByLineServer {
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

                processMessage(message);

                // Publish the message to the exchange for clients to update their text areas
                channel.basicPublish(EXCHANGE_NAME, "", null, message.getBytes(StandardCharsets.UTF_8));
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

    private static void processMessage(String message) {
        // Example message format: "fileId::0:Hi there;3:is Everything okay?;"
        String[] parts = message.split("::",2);
        if (parts.length != 2) {
            System.err.println("Invalid message format");
            return;
        }

        String filePath = parts[0];
        String changes = parts[1];

        Map<Integer, String> changesMap = new HashMap<>();

        String[] lineChanges = changes.split(";");
        for (String lineChange : lineChanges) {
            String[] lineParts = lineChange.split(":", 2);
            if (lineParts.length != 2) {
                System.err.println("Invalid line change format: " + lineChange);
                continue;
            }

            try {
                int lineNumber = Integer.parseInt(lineParts[0]);
                String content = lineParts[1];
                changesMap.put(lineNumber, content);
            } catch (NumberFormatException e) {
                System.err.println("Invalid line number format: " + lineParts[0]);
            }
        }

        updateFile(filePath, changesMap);
    }

    private static void updateFile(String filePath, Map<Integer, String> changesMap) {
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                System.err.println("File does not exist: " + filePath);
                return;
            }

            // Read all lines from the file
            Path path = Paths.get(filePath);
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

            // Apply changes
            for (Map.Entry<Integer, String> entry : changesMap.entrySet()) {
                int lineNumber = entry.getKey();
                String content = entry.getValue();

                if (lineNumber >= 0) {
                    if(lineNumber >= lines.size()) {
                        for (int i = lines.size(); i < lineNumber; i++) {
                            lines.add(i, "");
                        }
                        lines.add(lineNumber, content);
                    }
                    else
                        lines.set(lineNumber, content);
                } else {
                    System.err.println("Line number out of bounds: " + lineNumber);
                }
            }

            // Write the updated lines back to the file
            Files.write(path, lines, StandardCharsets.UTF_8);
            System.out.println("File updated: " + filePath);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

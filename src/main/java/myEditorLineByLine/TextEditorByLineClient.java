package myEditorLineByLine;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeoutException;

@SuppressWarnings("ALL")
public class TextEditorByLineClient extends JFrame {
    private static final String QUEUE_NAME = "text_changes_queue";
    private static final String EXCHANGE_NAME = "text_updates";
    private static final String CLIENTS_EXCHANGE = "clients_updates";
    private JTextArea textArea;
    private JLabel clientCountLabel;
    private JLabel fileNameLabel;
    private Channel channel;
    private Timer timer;
    private Map<Integer, String> lineContentMap = new HashMap<>();
    private String filePath;
    private String fileId;
    private boolean ignoreChanges = false;

    public TextEditorByLineClient() {
        try {
            // Set up RabbitMQ connection and channel
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost("localhost");
            Connection connection = factory.newConnection();
            channel = connection.createChannel();
            channel.queueDeclare(QUEUE_NAME, false, false, false, null);
            channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.FANOUT);
            String updateQueueName = channel.queueDeclare().getQueue();
            channel.queueBind(updateQueueName, EXCHANGE_NAME, "");

            // Create the text editor GUI
            setTitle("Text Editor Client");
            textArea = new JTextArea();
            JScrollPane scrollPane = new JScrollPane(textArea);

            clientCountLabel = new JLabel("Connected clients: 0");
            fileNameLabel = new JLabel("No file opened");
            add(fileNameLabel, BorderLayout.SOUTH);
            add(clientCountLabel, BorderLayout.NORTH);
            add(scrollPane, BorderLayout.CENTER);

            // Create menu bar
            JMenuBar menuBar = new JMenuBar();
            JMenu fileMenu = new JMenu("File");
            JMenuItem openMenuItem = new JMenuItem("Open");
            openMenuItem.addActionListener(e -> openFile());
            fileMenu.add(openMenuItem);
            menuBar.add(fileMenu);
            setJMenuBar(menuBar);

            setSize(800, 600);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

            setVisible(true);

            // Show file chooser dialog after GUI is set up
            SwingUtilities.invokeLater(this::openFile);

            textArea.getDocument().addDocumentListener(getDocumentListener());

            // Listen for updates from the server
            channel.basicConsume(updateQueueName, true, (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                String[] parts = message.split("::", 2);
                if (parts.length == 2 && parts[0].equals(fileId)) {
                    String[] lines = parts[1].split(";");
//                    Map<Integer, String> receivedLineContentMap = new HashMap<>();

                    for (String line : lines) {
                        if (!line.isEmpty()) {
                            String[] lineParts = line.split(":", 2);
                            if (lineParts.length == 2) {
                                int lineNumber = Integer.parseInt(lineParts[0]);
                                String content = lineParts[1];
                                try {
                                    Document doc = textArea.getDocument();
                                    int lineStart = textArea.getLineStartOffset(lineNumber);

                                    int endCheck = textArea.getLineEndOffset(lineNumber);
                                    int lineEnd = endCheck-1 > 0 && endCheck - lineStart > 0 ? endCheck-1 : endCheck;
                                    ignoreChanges = true;

                                    doc.remove(lineStart, lineEnd - lineStart);
                                    doc.insertString(lineStart, lineParts[1], null);
                                } catch (BadLocationException e) {
                                    throw new RuntimeException(e);
                                }
                                finally{
                                    ignoreChanges = false;
                                }
                            }
                        }
                    }
                }
            }, consumerTag -> { });

            // Listen for client count updates
            String clientsUpdateQueueName = channel.queueDeclare().getQueue();
            channel.queueBind(clientsUpdateQueueName, CLIENTS_EXCHANGE, "");
            channel.basicConsume(clientsUpdateQueueName, true, (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                SwingUtilities.invokeLater(() -> clientCountLabel.setText("Connected clients: " + message));
            }, consumerTag -> { });

            // Register with the server
            registerClient();

            // Unregister from the server when closing
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    unregisterClient();
                    System.exit(0);
                }
            });
        } catch (IOException | TimeoutException e) {
            e.printStackTrace();
        }
    }

    private void updateFileNameLabel(String filePath) {
        File file = new File(filePath);
        fileNameLabel.setText("Editing file: " + file.getName());
    }

    private void openFile() {
        JFileChooser fileChooser = new JFileChooser(new File("."));
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            filePath = fileChooser.getSelectedFile().getAbsolutePath();
            fileId = filePath;
            updateFileNameLabel(filePath);
            readFileContent();
        }
        else {
            // If no file is selected, just close the application
            unregisterClient();
            System.exit(0);
        }
    }


    private void readFileContent() {
        if (filePath == null || filePath.isEmpty()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineContentMap.put(lineNumber, line);
                lineNumber++;
            }
            // Set the initial content of the text area
            StringBuilder content = new StringBuilder();
            for (String value : lineContentMap.values()) {
                content.append(value).append(System.lineSeparator());
            };
            textArea.setText(content.toString());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private DocumentListener getDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                if (!ignoreChanges) {
                    resetTimer();
                }
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                if (!ignoreChanges) {
                    resetTimer();
                }
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                if (!ignoreChanges) {
                    resetTimer();
                }
            }
        };
    }

    private void resetTimer() {
        if (timer != null) {
            timer.cancel();
        }
        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                sendChanges();
            }
        }, 2000); // Push updated every 2 seconds
    }

    private void sendChanges() {
        String text = textArea.getText();
        text = text.replace("\r", "");
        Map<Integer, String> currentLineContentMap = new HashMap<>();
        String[] lines = text.split("\n");
        int lineNumber = 0;
        for (String line:lines) {
            currentLineContentMap.put(lineNumber,line);
            lineNumber++;
        }

        if (!currentLineContentMap.equals(lineContentMap)) {
            try {
                StringBuilder messageBuilder = new StringBuilder();
                if(currentLineContentMap.size() >= lineContentMap.size()) {
                    for (Map.Entry<Integer, String> entry : currentLineContentMap.entrySet()) {
                        Integer key = entry.getKey();
                        String currentValue = entry.getValue();
                        String previousValue = lineContentMap.getOrDefault(key,"");;

                        // Only append the entry if the value is different from the previous value
                        if (!currentValue.equals(previousValue)) {
                            messageBuilder.append(key).append(":").append(currentValue).append(";");
                        }
                    }
                }
                else
                {
                    for (Map.Entry<Integer, String> entry : lineContentMap.entrySet()) {
                        Integer key = entry.getKey();
                        String currentValue = currentLineContentMap.getOrDefault(key, "");

                        String previousValue = lineContentMap.getOrDefault(key,null);

                        // Only append the entry if the value is different from the previous value
                        if (!currentValue.equals(previousValue)) {
                            messageBuilder.append(key).append(":").append(currentValue).append(";");
                        }
                    }
                }
                String message = fileId + "::" + messageBuilder.toString();
                if (!messageBuilder.toString().isBlank()){
                    channel.basicPublish("", QUEUE_NAME, null, message.getBytes(StandardCharsets.UTF_8));
                    System.out.println("Changes sent to server: " + message);
                }
                lineContentMap = new HashMap<>(currentLineContentMap); // Update the map with the current content
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }


    private void registerClient() {
        try {
            channel.basicPublish(CLIENTS_EXCHANGE, "", null, "register".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void unregisterClient() {
        try {
            channel.basicPublish(CLIENTS_EXCHANGE, "", null, "unregister".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(TextEditorByLineClient::new);
    }
}

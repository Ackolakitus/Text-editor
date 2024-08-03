package myEditor;

import com.rabbitmq.client.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeoutException;

public class TextEditorClient extends JFrame {
    private static final String QUEUE_NAME = "text_changes_queue";
    private static final String EXCHANGE_NAME = "text_updates";
    private static final String CLIENTS_EXCHANGE = "clients_updates";
    private JTextArea textArea;
    private JLabel clientCountLabel;
    private JLabel fileNameLabel;
    private Channel channel;
    private Timer timer;
    private String lastSentText = "";
    private String filePath;
    private String fileId;

    public TextEditorClient() {
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
            textArea.setLineWrap(true); // Enable line wrapping
            textArea.setWrapStyleWord(true); // Wrap at word boundaries
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

            setSize(600, 800);
            setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            setVisible(true);

            // Show file chooser dialog after GUI is set up
            SwingUtilities.invokeLater(this::showFileChooser);

            textArea.getDocument().addDocumentListener(getDocumentListener());

            // Listen for updates from the server
            channel.basicConsume(updateQueueName, true, (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
                String[] parts = message.split("::", 2);
                if (parts.length == 2 && parts[0].equals(fileId)) {
                    String fileContent = parts[1];
                    SwingUtilities.invokeLater(() -> {
                        textArea.getDocument().removeDocumentListener(getDocumentListener());
                        textArea.setText(fileContent);
                        lastSentText = fileContent;
                        textArea.getDocument().addDocumentListener(getDocumentListener());
                    });
                }
            }, consumerTag -> {
            });

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

    private void showFileChooser() {
        JFileChooser fileChooser = new JFileChooser(new File("."));
        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            filePath = fileChooser.getSelectedFile().getAbsolutePath();
            fileId = filePath;
            updateFileNameLabel(filePath);
            readFileContent();
        } else {
            // If no file is selected, just close the application
            unregisterClient();
            System.exit(0);
        }
    }

    private void updateFileNameLabel(String filePath) {
        File file = new File(filePath);
        fileNameLabel.setText("Editing file: " + file.getName());
    }

    private void readFileContent() {
        if (filePath == null || filePath.isEmpty()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line).append(System.lineSeparator());
            }
            textArea.setText(content.toString());
            lastSentText = content.toString(); // Set lastSentText to the current file content
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private DocumentListener getDocumentListener() {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                resetTimer();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                resetTimer();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                resetTimer();
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
        if (!text.equals(lastSentText)) {
            try {
                String message = fileId + "::" + text;
                channel.basicPublish("", QUEUE_NAME, null, message.getBytes(StandardCharsets.UTF_8));
                System.out.println("Changes sent to server: " + message);
                lastSentText = text;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
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
        SwingUtilities.invokeLater(TextEditorClient::new);
    }
}

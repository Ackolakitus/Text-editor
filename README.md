# RabbitMQ Shared Text Editor
This repository contains a shared text editor built using RabbitMQ, designed to efficiently update only the lines that have changed rather than sending the entire file. This approach minimizes data transfer and improves the performance of collaborative editing.

## Features
- **Line-by-Line Updates:** Only the modified lines are sent to the server, reducing the amount of data transmitted.
- **RabbitMQ Integration:** The editor uses RabbitMQ to manage message passing between the client and server, ensuring reliable and efficient communication.
- **Concurrency Control:** The system is designed to handle multiple users editing the same file simultaneously, maintaining consistency and avoiding conflicts.
## Architecture
The project consists of two main components:

1. **Client:** The client-side application is responsible for detecting changes made by the user and sending only the modified lines to the server.
2. **Server:** The server-side application receives the updates from the client, applies them to the file, and ensures that all connected clients are updated accordingly.

### How It Works
1. **Change Detection:** The client continuously monitors the text file for changes. When a change is detected, it identifies the specific lines that were modified.
2. **Message Sending:** The modified lines, along with their line numbers, are sent as a message to the server using RabbitMQ.
3. **Update Application:** The server processes the received message and updates the corresponding lines in the file. It then broadcasts the changes to all connected clients to ensure they are all in sync.

### Usage
1. Start the server first to listen for incoming connections.
2. Launch the client, and you can begin editing the text file. The client will automatically detect changes and send only the modified lines to the server.
3. The server will update the file and notify all other clients of the changes, ensuring everyone is on the same page.

import java.net.*;
import java.io.*;
import java.nio.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.Timer;
import java.util.logging.FileHandler;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class peerProcess {
    private int node_identifier;
    private int preferred_peer_limit;
    private long unchoke_period;
    private long optimistic_unchoke_period;
    private String target_file;
    private int total_file_bytes;
    private int connection_port;
    private boolean is_file_downloaded;
    private Peer process;
    private ArrayList<Peer> currentPeerNodes;
    private ArrayList<Peer> potential_peers;
    private HashMap<Integer, PeerMessageProcessor> active_peers;
    private ArrayList<Integer> desiredNeighbors;
    private int optimistic_unchoked;
    private int file_segment_size;
    private volatile Bitfield piece_map;
    private volatile byte[][] file_segments;
    private volatile HashMap<Integer, Double> rates;

    public class Peer {
        int node_identifier;
        String address;
        int port;
        boolean is_file_downloaded;

        public Peer(int id, String address, int port, boolean is_file_downloaded) {
            this.node_identifier = id;
            this.address = address;
            this.port = port;
            this.is_file_downloaded = is_file_downloaded;
        }
    }

    public static void main(String[] args) throws Exception {
    // Ensure a command line argument for peer ID is provided
    if (args.length == 0) {
        throw new Exception("Error: Peer ID must be provided");
    }

    // Check if the peer ID is a valid integer
    int peer_ID_input;
    try {
        peer_ID_input = Integer.parseInt(args[0]);
    } catch (NumberFormatException e) {
        throw new Exception("Error: Peer ID must be an integer");
    }

    // Delete any existing log files for the given peer ID
    File folder = new File(".");
    for (File f : folder.listFiles()) {
        if (f.getName().endsWith(peer_ID_input + ".log")) {
            f.delete();
        }
    }

    // Create and start the peer process for the specified peer ID
    peerProcess peer_process = new peerProcess(peer_ID_input);
}

    public void selectAndUnchokeRandomPeer() {
        // get list of peers
        ArrayList<PeerMessageProcessor> possiblePeers = new ArrayList<>();
        Object[] temp = active_peers.values().toArray();
        for (Object obj : temp) {
            possiblePeers.add((PeerMessageProcessor) obj);
        }

        // then, get only peers that are interested in what we have, and they are choked
        possiblePeers.removeIf(peer -> !peer.client_interested || !peer.client_choked);

        // pick a random one out of those
        if (possiblePeers.size() > 0) {
            int random_index = new Random().nextInt(possiblePeers.size());

            PeerMessageProcessor optimistic_unchoked = possiblePeers.get(random_index);

            optimistic_unchoked.logOptimisticUnchokingEvent();
            optimistic_unchoked.unChokePeerConnection();
        }
    }

    private class Bitfield {
    byte[] bits;

    private HashMap<Integer, Integer> currentlyRequesting = new HashMap<>();

    public synchronized void markPieceAsDownloaded(int index) {
        bits[index] = (byte) 1;
    }

    public void broadcastFileCompletionToAllPeers() {
        for (PeerMessageProcessor thread : active_peers.values()) {
            thread.transmitBitfield();
        }
    }

    public boolean checkIfAllPiecesAreDownloaded() {
        for (byte hasPiece : bits) {
            if (hasPiece == 0) {
                return false;
            }
        }
        
        return true;
    }

    public synchronized void addPieceRequest(int index, int id) {
        currentlyRequesting.put(id, index);
    }

    public synchronized void removePieceRequest(int index) {
        ArrayList<Integer> removal = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : currentlyRequesting.entrySet()) {
            if (entry.getValue() == index) {
                removal.add(entry.getKey());
            }
        }
        for (Integer key : removal) {
            currentlyRequesting.remove(key);
        }
    }

    public void broadcastNewPieceAvailability(int index) {
        for (PeerMessageProcessor thread : active_peers.values()) {
            if (thread.clientBitfield.bits[index] == 0) {
                thread.notifyPeerOfNewPiece(index);
            }
        }
    }

    }

    public void identifyTopPeersBasedOnRate() {
    ArrayList<Integer> preferred = new ArrayList<>();
    
    // Select top peers based on their download rates
    for (int i = 0; i < this.preferred_peer_limit; i++) {
        HashMap<Integer, Double> temp = new HashMap<>();
        double fastest = 0;

        // If no peers are downloading or all have files, randomly select peers
        if (Collections.max(this.rates.values()) == 0 || this.active_peers.get(this.rates.keySet().toArray()[0]).host_has_file) {
            Random rand = new Random();
            ArrayList<Integer> arrRates = new ArrayList<>();
            
            for (int key : this.rates.keySet()) {
                if (this.active_peers.get(key).client_interested) {
                    arrRates.add(key);
                }
            }

            int index = 0;
            do {
                if (arrRates.isEmpty()) {
                    break;
                }
                index = rand.nextInt(arrRates.size());
            } while (preferred.contains(arrRates.get(index)) && !arrRates.isEmpty());

            if (index < arrRates.size() && !preferred.contains(arrRates.get(index))) {
                preferred.add(arrRates.get(index));
            }
        } else {
            // Otherwise, select based on the highest rates
            for (Map.Entry<Integer, Double> entry : this.rates.entrySet()) {
                if (preferred.contains(entry.getKey())) {
                    continue;
                }
                if (temp.isEmpty() && this.active_peers.get(entry.getKey()).client_interested) {
                    temp.put(entry.getKey(), entry.getValue());
                } else if (temp.isEmpty()) {
                    continue;
                } else if (Collections.min(temp.values()) == 0 || Collections.min(temp.values()) > entry.getValue() && this.active_peers.get(entry.getKey()).client_interested) {
                    temp.clear();
                    temp.put(entry.getKey(), entry.getValue());
                } else if (Collections.min(temp.values()) == entry.getValue() && this.active_peers.get(entry.getKey()).client_interested) {
                    temp.put(entry.getKey(), entry.getValue());
                } else {
                    continue;
                }
            }
        }

        // Randomly select among peers with similar rates
        Random rand = new Random();
        Object[] arrRates = temp.keySet().toArray();
        if (!temp.isEmpty()) {
            int index = rand.nextInt(temp.size());
            preferred.add((Integer) arrRates[index]);
        }
    }

    // Update preferred neighbors and handle choking/unchoking
    for (int i = 0; i < this.preferred_peer_limit; i++) {
        if (preferred.size() > i && this.desiredNeighbors.contains(preferred.get(i))) {
            continue;
        } else if (this.desiredNeighbors.size() > i && !preferred.contains(this.desiredNeighbors.get(i))) {
            this.active_peers.get(this.desiredNeighbors.get(i)).chokePeerConnection();
        } else if (preferred.size() > i && !this.desiredNeighbors.contains(preferred.get(i))) {
            this.active_peers.get(preferred.get(i)).unChokePeerConnection();
            this.active_peers.get(preferred.get(i)).logPreferredPeerSelection();
        }
    }
    this.desiredNeighbors = preferred;
}


    // is responsible for setting class variables using the peer_ID, the Common.cfg, and PeerInfo.cfg
    public peerProcess(int p_ID) {
        this.node_identifier = p_ID;

        // reading Common.cfg
        Properties common = new Properties();
        try (FileInputStream fis = new FileInputStream("Common.cfg")) {
            common.load(fis);
            fis.close();
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }

        this.preferred_peer_limit = Integer.parseInt(common.getProperty("NumberOfPreferredNeighbors"));
        this.unchoke_period = Integer.parseInt(common.getProperty("UnchokingInterval"));
        this.optimistic_unchoke_period = Integer.parseInt(common.getProperty("OptimisticUnchokingInterval"));

        this.target_file = common.getProperty("FileName");
        this.total_file_bytes = Integer.parseInt(common.getProperty("FileSize"));
        this.file_segment_size = Integer.parseInt(common.getProperty("PieceSize"));
        this.desiredNeighbors = new ArrayList<>();

        // setting bitfield
        int bitfield_size = determineBitfieldLength(this.total_file_bytes, this.file_segment_size);
        this.piece_map = new Bitfield();
        this.piece_map.bits = new byte[bitfield_size];
        this.file_segments = new byte[bitfield_size][];

        // reading PeerInfo.cfg
        this.currentPeerNodes = new ArrayList<>();
        this.potential_peers = new ArrayList<>();
        this.active_peers = new HashMap<>();
        this.rates = new HashMap<Integer, Double>();
        File file = new File("PeerInfo.cfg");
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            for (String line; (line = br.readLine()) != null; ) {

                String[] split = line.split(" ");

                int new_id = Integer.parseInt(split[0]);
                String new_address = split[1];
                int new_port = Integer.parseInt(split[2]);
                boolean is_file_downloaded = Integer.parseInt(split[3]) == 1;

                if (new_id != this.node_identifier) {
                    this.currentPeerNodes.add(new Peer(new_id, new_address, new_port, is_file_downloaded));
                    this.rates.put(new_id, 0.0);
                } else {
                    process = new Peer(this.node_identifier, new_address, new_port, is_file_downloaded);
                    this.connection_port = new_port;
                    this.is_file_downloaded = is_file_downloaded;
                    break;
                }

            }
            // keep reading after existingPeers stopped
            for (String line; (line = br.readLine()) != null; ) {
                String[] split = line.split(" ");

                int new_id = Integer.parseInt(split[0]);
                String new_address = split[1];
                int new_port = Integer.parseInt(split[2]);
                boolean is_file_downloaded = Integer.parseInt(split[3]) == 1;

                this.potential_peers.add(new Peer(new_id, new_address, new_port, is_file_downloaded));
                this.rates.put(new_id, 0.0);
            }
        } catch (IOException ioException) {
            ioException.printStackTrace();
        }
        File dir = new File("./" + String.valueOf(node_identifier));
        if (!dir.exists())
        {
            dir.mkdirs();
        }

        // setting bitfield default value based on having the file
        if (this.is_file_downloaded) {
            Arrays.fill(piece_map.bits, (byte) 1);
            Path path = Paths.get("./" + this.node_identifier + "/" + target_file);
            byte[] fileBytes = new byte[0];
            
            try {
                fileBytes = Files.readAllBytes(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
            int numBytes = file_segment_size;
            for (int i = 0; i < bitfield_size; i++) {
                if (i == bitfield_size - 1)
                {
                    numBytes = total_file_bytes - (i * file_segment_size);
                }
                byte[] temp = new byte[numBytes];
                if (numBytes - 1 >= 0) {
                    System.arraycopy(fileBytes, (i * file_segment_size), temp, 0, numBytes);
                }
                file_segments[i] = temp;
            }

        } else {
            Arrays.fill(piece_map.bits, (byte) 0);
            piece_map.bits[0] = (byte) 0;
        }

        // function to connect the process to the others
        establishConnectionsWithPeers();

        Timer time = new Timer(); // Instantiate Timer Object
        PeriodicNeighborSelector choosePreferred = new PeriodicNeighborSelector();
        OptimisticUnchokeTask optimisticUnchoke = new OptimisticUnchokeTask();

        time.schedule(choosePreferred, 1, ((long) 1000 * unchoke_period));
        time.schedule(optimisticUnchoke, 1, ((long) 1000 * optimistic_unchoke_period));

        boolean ok = false;
        while (!ok) {
            ok = isDownloadCompleteForAllPeers();
        }

        int file_length = 0;

        for (byte[] piece : this.file_segments) {
            file_length += piece.length;
        }


        byte[] file_data = new byte[file_length];
        int amount_read = 0;

        for (byte[] piece : this.file_segments) {
            System.arraycopy(piece, 0, file_data, amount_read, piece.length);
            amount_read += piece.length;
        }

        if (!process.is_file_downloaded) {
            try {
                OutputStream os = new FileOutputStream("./" + this.node_identifier + "/" + target_file);
                os.write(file_data);
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        

        choosePreferred.cancel();
        optimisticUnchoke.cancel();

        for (PeerMessageProcessor thread : active_peers.values()) {
            thread.client_has_file = true;
            thread.client_interested = false;
            thread.host_has_file = true;
            thread.host_interested = false;

            thread.done = true;
        }

        System.exit(0);
    }

    public void establishConnectionsWithPeers() {
    try {
        // Connect to existing peers
        if (this.currentPeerNodes.size() > 0) {
            for (Peer current : this.currentPeerNodes) {
                // Skip connecting to self
                if (current.node_identifier == this.node_identifier) {
                    continue;
                }

                // Create a socket to connect to the peer
                Socket requestSocket = new Socket(current.address, current.port);

                // Initialize the bitfield for the client
                Bitfield client_bitfield = new Bitfield();
                client_bitfield.bits = new byte[piece_map.bits.length];

                // Create and start a handler for communication
                PeerMessageProcessor handler = new PeerMessageProcessor(requestSocket, current, process, this.piece_map, client_bitfield, this.file_segments, this.rates);
                handler.initiateConnectionHandshake();
                handler.start();

                // Add the handler to the connection list
                this.active_peers.put(current.node_identifier, handler);
            }
        }

        // Listen for future peers and accept their connections
        if (this.potential_peers.size() > 0) {
            ServerSocket listener = new ServerSocket(this.connection_port);

            for (Peer future : this.potential_peers) {
                // Initialize the bitfield for the client
                Bitfield client_bitfield = new Bitfield();
                client_bitfield.bits = new byte[piece_map.bits.length];

                // Create and start a handler for communication
                PeerMessageProcessor handler = new PeerMessageProcessor(listener.accept(), future, process, this.piece_map, client_bitfield, this.file_segments, this.rates);
                handler.waitForConnectionHandshake();
                handler.start();

                // Add the handler to the connection list
                this.active_peers.put(future.node_identifier, handler);
            }
        }
    } catch (IOException ioException) {
        ioException.printStackTrace();
    }
}


// Calculate the number of pieces in a file based on file size and piece size
public int determineBitfieldLength(int total_file_bytes, int file_segment_size) {
    return (int) Math.ceil(((float) total_file_bytes) / ((float) file_segment_size));
}



 public boolean isDownloadCompleteForAllPeers() {
    // Check if this peer has downloaded all pieces
    if (!this.piece_map.checkIfAllPiecesAreDownloaded()) {
        return false;
    }

    // Check if all connected peers have downloaded all pieces
    for (PeerMessageProcessor peer : active_peers.values()) {
        if (!peer.clientBitfield.checkIfAllPiecesAreDownloaded()) {
            return false;
        }
    }
    return true;  // Return true if all peers have completed the download
}

public class PeriodicNeighborSelector extends TimerTask {
    // Periodically selects top peers based on their download rate
    public void run() {
        identifyTopPeersBasedOnRate();
    }
}


private static class PeerMessageProcessor extends Thread {
// Socket representing the connection between peers.
private Socket connection;

// Input stream to read data from the connected peer's socket.
private DataInputStream in;

// Output stream to send data to the connected peer's socket.
private DataOutputStream out;

// Unique identifier for the connected peer.
private int hostId;

// Unique identifier for the current peer (this peer).
private int clientId;

// Logger instance for recording events and debugging information.
private Logger logger = Logger.getLogger("MyLog");

// Bitfield representing the file pieces owned by the connected peer.
private Bitfield hostBitfield;

// Bitfield representing the file pieces owned by the current peer.
private Bitfield clientBitfield;

// Array to store the actual file pieces being exchanged.
private byte[][] pieces;

// Stores download rates for each piece from the connected peer.
private HashMap<Integer, Double> rates;

// Flags to track if either peer is interested in the other's pieces.
boolean host_interested = false; // Whether the connected peer is interested in the current peer's pieces.
boolean client_interested = false; // Whether the current peer is interested in the connected peer's pieces.

// Flags to track if either peer is choked (restricted from downloading).
boolean host_choked = false; // Whether the connected peer has choked the current peer.
boolean client_choked = false; // Whether the current peer has choked the connected peer.

// Flags to track if either peer has completed downloading the file.
boolean host_has_file = false; // Whether the connected peer has the complete file.
boolean client_has_file = false; // Whether the current peer has the complete file.

// Flag to indicate if the communication between peers is complete.
boolean done = false;

// Main method to handle communication with the connected peer.
public void run() {
    // Log the start of communication between peers.
    writeLogMessage("Peer " + hostId + " can start sending packets to " + clientId);

    // Send the bitfield of the current peer to the connected peer.
    transmitBitfield();

    // Loop to handle incoming messages until the connection is terminated.
    while (true) {
        try {
            // Buffer to store the length of the incoming message (4 bytes).
            byte[] msgLen = new byte[4];

            // Read the length of the incoming message.
            int length_read = in.read(msgLen);

            // If no data is received, continue waiting for messages.
            if (length_read == 0) {
                continue;
            }

            // Read the message type (1 byte).
            byte msg_type = in.readByte();

            // Check if the message contains a payload.
            boolean read_payload = has_payload(msg_type);

            // Process the message based on whether it has a payload.
            if (read_payload) {
                // Handle a message with a payload.
                receiveMessageWithPayload(msg_type, convertBytesToInteger(msgLen) - 1);
            } else {
                // Handle a message without a payload.
                processMessageWithoutPayload(msg_type);
            }

            // If both peers have the complete file, reset interest flags.
            if (host_has_file && client_has_file) {
                host_interested = false;
                client_interested = false;
            }

            // Exit the loop if communication is marked as done.
            if (done) {
                break;
            }
        } catch (IOException e) {
            // Exit the loop on an I/O error.
            break;
        }
    }

    // Log the end of communication between peers.
    writeLogMessage("Peer " + hostId + " has finished communication with " + clientId);

    // Close the connection and terminate the thread.
    try {
        connection.close();
    } catch (IOException e) {
        return;
    }
    Thread.currentThread().interrupt();
}

    private void processMessageWithoutPayload(byte type) {
    // Update state variables based on message type
    if (type == CHOKE) {
        host_choked = true; // Host is choked, stop receiving pieces
        writeLogMessage("Peer " + hostId + " received choke message from Peer " + clientId);
        hostBitfield.currentlyRequesting.remove(this.clientId); // Remove requests from the host
    } else if (type == UNCHOKE) {
        host_choked = false; // Host is unchoked, can receive pieces
        writeLogMessage("Peer " + hostId + " received unchoke message from Peer " + clientId);

        // Check if the host is interested in pieces from the client
        host_interested = checkForMissingPieces(clientBitfield.bits);

        // Request a missing piece if interested
        if (host_interested) {
            int next_piece = selectRandomMissingPiece();
            if (next_piece >= 0) {
                hostBitfield.addPieceRequest(this.clientId, next_piece);
                requestFilePiece(next_piece); // Request the next piece
            }
        }
    } else if (type == INTERESTED) {
        client_interested = true; // Client is interested in receiving pieces
        writeLogMessage("Peer " + hostId + " received interest message from Peer " + clientId);

    } else if (type == NOT_INTERESTED) {
        client_interested = false; // Client is no longer interested
        writeLogMessage("Peer " + hostId + " received not interested message from Peer " + clientId);
    }
}
            public void initiateConnectionHandshake() {
    try {
        writeLogMessage("Initiating existing peer handshake: " + clientId);

        // Prepare the handshake message
        ByteArrayOutputStream send = new ByteArrayOutputStream(32);

        byte[] header = "P2PFILESHARINGPROJ".getBytes(); // Protocol identifier
        byte[] zeros = new byte[10]; // Padding
        byte[] p_id = ByteBuffer.allocate(4).putInt(this.hostId).array(); // Peer ID

        // Build the handshake message
        send.write(header, 0, 18);
        send.write(zeros, 0, 10);
        send.write(p_id, 0, 4);

        writeLogMessage("Peer " + this.hostId + " makes a connection to " + this.clientId + ".");
        out.write(send.toByteArray()); // Send the handshake message

        acknowledgeHandshakeCompletion(); // Acknowledge the handshake
    } catch (IOException ioException) {
        ioException.printStackTrace();
    }
}

    private void receiveMessageWithPayload(byte type, int msgLen) {
    int curr_read = 0;
    byte[] payload = new byte[msgLen]; // Store the received message payload
    int current_package_length;

    double start = System.nanoTime(); // Start timing the message reception
    while (curr_read < msgLen) {
        byte[] data = new byte[msgLen - curr_read];
        try {
            current_package_length = in.read(data, 0, data.length); // Read data from input stream
            System.arraycopy(data, 0, payload, curr_read, current_package_length); // Append data to payload
            curr_read += current_package_length; // Update the number of bytes read
        } catch (IOException e) {
            break; // Exit if there's an error reading
        }
    }
    double end = System.nanoTime(); // End timing

    // Handle different message types
    if (type == BITFIELD) {
        writeLogMessage("Peer " + hostId + " received BITFIELD from Peer " + clientId);
        clientBitfield.bits = payload; // Update the client's bitfield
        client_has_file = clientBitfield.checkIfAllPiecesAreDownloaded(); // Check if the client has the full file

        if (client_has_file) {
            client_interested = false; // No need to keep interest if the client has all pieces
        }

        host_interested = checkForMissingPieces(clientBitfield.bits); // Check if the host is interested in the pieces
        if (host_interested) {
            signalInterestToPeer(); // Signal interest if the host is missing pieces
        } else {
            signalLackOfInterestToPeer(); // Otherwise, signal no interest
        }

    } else if (type == HAVE) {
        byte[] indexBytes = Arrays.copyOfRange(payload, 0, 4);
        int index = convertBytesToInteger(indexBytes); // Get the piece index
        writeLogMessage("Peer " + hostId + " received HAVE from Peer " + clientId + " for piece " + index);

        clientBitfield.markPieceAsDownloaded(index); // Mark the piece as downloaded
        client_has_file = clientBitfield.checkIfAllPiecesAreDownloaded(); // Update the download status

        boolean temp = checkForMissingPieces(clientBitfield.bits); // Check if there are more pieces to request
        if (temp && !host_interested) {
            host_interested = temp;
            signalInterestToPeer(); // Request missing pieces if host is interested
        } else if (!temp && host_interested) {
            host_interested = temp;
            signalLackOfInterestToPeer(); // Stop interest if no more pieces are missing
        }

    } else if (type == REQUEST) {
        int index = convertBytesToInteger(payload); // Get the requested piece index
        writeLogMessage("Peer " + hostId + " received REQUEST from Peer " + clientId + " for piece " + index);

        // Only send the piece if it's available and client is unchoked
        if (hostBitfield.bits[index] == 0 || client_choked) {
            return;
        }
        transmitFilePiece(index); // Send the requested file piece to the client
    } else if (type == PIECE) {
        byte[] indexBytes = Arrays.copyOfRange(payload, 0, 4);
        int index = convertBytesToInteger(indexBytes); // Get the piece index
        pieces[index] = Arrays.copyOfRange(payload, 4, payload.length); // Store the received piece

        double rate = (end - start); // Measure the download rate
        this.rates.put(this.clientId, rate); // Update the download rate map

        hostBitfield.markPieceAsDownloaded(index); // Mark the piece as downloaded
        hostBitfield.removePieceRequest(index); // Remove the request for this piece

        int count = 0;
        for (int i = 0; i < hostBitfield.bits.length; i++) {
            if (hostBitfield.bits[i] == (byte) 1) {
                count++; // Count the downloaded pieces
            }
        }

        double percent = (double) count / hostBitfield.bits.length * 100.0; // Calculate download progress
        String progress = String.format("Piece count: %d. (%.2f)%%", count, percent);
        writeLogMessage("Peer " + hostId + " has downloaded the piece " + index + " from Peer " + clientId + ". " + progress);

        hostBitfield.broadcastNewPieceAvailability(index); // Notify other peers about the new piece

        // Check if the host has the full file and update accordingly
        host_has_file = hostBitfield.checkIfAllPiecesAreDownloaded();
        if (host_has_file) {
            hostBitfield.broadcastFileCompletionToAllPeers(); // Broadcast file completion to all peers
            writeLogMessage(String.format("Peer %d now has the entire file.", hostId));
        }

        boolean temp = checkForMissingPieces(clientBitfield.bits);
        if (temp && host_interested) {
            int next_piece = selectRandomMissingPiece(); // Select the next piece to download
            if (next_piece >= 0) {
                hostBitfield.addPieceRequest(next_piece, this.clientId);
                requestFilePiece(next_piece); // Request the next missing piece
            } else {
                return;
            }
        } else if (temp && !host_interested) {
            signalInterestToPeer(); // Signal interest if there are still missing pieces
            host_interested = temp;
        } else if (!temp && host_interested) {
            signalLackOfInterestToPeer(); // Stop interest if all pieces are received
            host_interested = temp;
        }
    }
}



public PeerMessageProcessor(Socket connection, Peer client, Peer host, Bitfield host_bitfield, Bitfield client_bitfield, byte[][] pieces, HashMap<Integer, Double> rates) {
    // Initialize peer connection and data
    this.connection = connection;

    this.pieces = pieces;
    this.hostId = host.node_identifier;
    this.hostBitfield = host_bitfield;
    this.host_has_file = host.is_file_downloaded;

    this.clientId = client.node_identifier;
    this.client_has_file = client.is_file_downloaded;
    this.clientBitfield = client_bitfield;

    this.rates = rates;

    // Initialize bitfields based on whether the client has the file
    if (client_has_file) {
        Arrays.fill(clientBitfield.bits, (byte) 1); // Mark all bits as downloaded

        if (!host_has_file) {
            host_interested = true; // Host is interested if it doesn't have the file
        }
    } else {
        Arrays.fill(clientBitfield.bits, (byte) 0); // Client does not have the file
    }

    // Set up logging
    FileHandler fh;
    InputStream loggerProps = peerProcess.class.getResourceAsStream("/logger.properties");

    try {
        // Configure the logger with handler and formatter
        LogManager.getLogManager().readConfiguration(loggerProps);
        fh = new FileHandler("./log_peer_" + this.hostId + ".log", true);
        logger.addHandler(fh);
        SimpleFormatter formatter = new SimpleFormatter();
        fh.setFormatter(formatter);
    } catch (SecurityException | IOException ex) {
        ex.printStackTrace();
    }

    // Initialize input and output streams for communication
    try {
        this.out = new DataOutputStream(this.connection.getOutputStream());
        this.out.flush();
        this.in = new DataInputStream(this.connection.getInputStream());
    } catch (IOException ioException) {
        ioException.printStackTrace();
    }
}




public void waitForConnectionHandshake() {
    try {
        writeLogMessage("Expected future peer handshake: " + clientId);

        // Read the handshake message
        byte[] receive = new byte[32];
        int bytesNum = in.read(receive);

        // Extract peer ID from the message
        int from = ByteBuffer.wrap(Arrays.copyOfRange(receive, 28, 32)).getInt();

        if (from != this.clientId) {
            writeLogMessage("Incorrect peerID received... <" + from + ">");
            Thread.currentThread().interrupt();
        }

        writeLogMessage("Peer " + this.hostId + " received connection request from Peer " + this.clientId + ".");
        sendHandshakeResponse(); // Send handshake response
    } catch (IOException ioException) {
        Thread.currentThread().interrupt();
    }
}

public void sendHandshakeResponse() {
    try {
        // Prepare the handshake response message
        byte[] header = "P2PFILESHARINGPROJ".getBytes(); // Protocol identifier
        byte[] zeros = new byte[10]; // Padding
        byte[] p_id = ByteBuffer.allocate(4).putInt(this.hostId).array(); // Peer ID

        ByteArrayOutputStream send = new ByteArrayOutputStream(32);

        // Build the handshake response message
        send.write(header, 0, 18);
        send.write(zeros, 0, 10);
        send.write(p_id, 0, 4);

        out.write(send.toByteArray()); // Send the response

        writeLogMessage("Peer " + this.hostId + " returned handshake to " + this.clientId + ".");
    } catch (IOException ioException) {
        Thread.currentThread().interrupt();
    }
}

public void signalInterestToPeer() {
    try {
        // Prepare the INTERESTED message to signal interest in the file
        ByteArrayOutputStream send = new ByteArrayOutputStream(5);
        byte[] length = ByteBuffer.allocate(4).putInt(1).array(); // Message length
        byte[] type = new byte[]{INTERESTED}; // Message type (INTERESTED)

        send.write(length, 0, 4); // Add length
        send.write(type, 0, 1); // Add type

        host_interested = true; // Mark the host as interested

        out.write(send.toByteArray()); // Send the message

        writeLogMessage("Peer " + hostId + " sent Interest message to Peer " + clientId);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt(); // Handle I/O exceptions
    }
}


public void acknowledgeHandshakeCompletion() {
    try {
        // Read the completion of the handshake
        byte[] receive = new byte[32];
        int bytesNum = in.read(receive);

        int from = ByteBuffer.wrap(Arrays.copyOfRange(receive, 28, 32)).getInt();

        if (from != this.clientId) {
            writeLogMessage("Incorrect peerID received... <" + from + ">");
            Thread.currentThread().interrupt();
        }

        writeLogMessage("Peer " + this.hostId + " successfully established connection to " + this.clientId + ".");
    } catch (IOException ioException) {
        Thread.currentThread().interrupt();
    }
}

public void transmitBitfield() {
    try {
        // Prepare the bitfield message
        byte[] length = ByteBuffer.allocate(4).putInt(hostBitfield.bits.length + 1).array();
        byte[] type = new byte[]{BITFIELD};
        byte[] payload = hostBitfield.bits;

        ByteArrayOutputStream send = new ByteArrayOutputStream(hostBitfield.bits.length + 5);
        send.write(length, 0, 4); // Message length
        send.write(type, 0, 1); // Message type (BITFIELD)
        send.write(payload, 0, hostBitfield.bits.length); // Payload (bitfield)

        out.write(send.toByteArray()); // Send the bitfield message

        writeLogMessage("Peer " + hostId + " sent BITFIELD message to Peer " + clientId);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt();
    }
}

public void transmitFilePiece(int index) {
    try {
        // Prepare the file piece message
        byte[] length = ByteBuffer.allocate(4).putInt(5 + pieces[index].length).array();
        byte[] type = new byte[]{PIECE};
        byte[] indexByte = ByteBuffer.allocate(4).putInt(index).array(); // Piece index
        byte[] payload = pieces[index]; // Piece data

        ByteArrayOutputStream send = new ByteArrayOutputStream(9 + pieces[index].length);
        send.write(length, 0, 4); // Message length
        send.write(type, 0, 1); // Message type (PIECE)
        send.write(indexByte, 0, 4); // Piece index
        send.write(payload, 0, pieces[index].length); // Payload (piece data)

        out.write(send.toByteArray()); // Send the file piece

        writeLogMessage("Peer " + hostId + " sent PIECE response to Peer " + clientId + " for piece " + index);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt();
    }
}


        public void notifyPeerOfNewPiece(int index) {
    try {
        // Prepare the HAVE message to notify the peer about a new piece
        ByteArrayOutputStream send = new ByteArrayOutputStream(9);
        byte[] length = ByteBuffer.allocate(4).putInt(5).array(); // Message length
        byte[] type = new byte[]{HAVE}; // Message type (HAVE)
        byte[] payload = ByteBuffer.allocate(4).putInt(index).array(); // Piece index

        send.write(length, 0, 4); // Add length
        send.write(type, 0, 1); // Add type
        send.write(payload, 0, 4); // Add piece index

        out.write(send.toByteArray()); // Send the message

        writeLogMessage("Peer " + hostId + " sent HAVE message to Peer " + clientId + " for piece " + index);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt(); // Handle I/O exceptions
    }
}

public void requestFilePiece(int index) {
    try {
        // Prepare the REQUEST message to request a piece from the peer
        ByteArrayOutputStream send = new ByteArrayOutputStream(9);
        byte[] length = ByteBuffer.allocate(4).putInt(5).array(); // Message length
        byte[] type = new byte[]{REQUEST}; // Message type (REQUEST)
        byte[] payload = ByteBuffer.allocate(4).putInt(index).array(); // Piece index

        send.write(length, 0, 4); // Add length
        send.write(type, 0, 1); // Add type
        send.write(payload, 0, 4); // Add piece index

        out.write(send.toByteArray()); // Send the request

        writeLogMessage("Peer " + hostId + " sent Request message to Peer " + clientId + " for piece " + index);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt(); // Handle I/O exceptions
    }
}


public void signalLackOfInterestToPeer() {
    try {
        // Prepare the NOT INTERESTED message to signal lack of interest
        ByteArrayOutputStream send = new ByteArrayOutputStream(5);
        byte[] length = ByteBuffer.allocate(4).putInt(1).array(); // Message length
        byte[] type = new byte[]{NOT_INTERESTED}; // Message type (NOT_INTERESTED)

        send.write(length, 0, 4); // Add length
        send.write(type, 0, 1); // Add type

        host_interested = false; // Mark the host as not interested

        out.write(send.toByteArray()); // Send the message

        writeLogMessage("Peer " + hostId + " sent Not Interested message to Peer " + clientId);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt(); // Handle I/O exceptions
    }
}

public int selectRandomMissingPiece() {
    // Create a map to store pieces that are available in the client bitfield but missing in the host bitfield
    HashMap<Integer, Byte> possiblePieces = new HashMap<>();

    // Identify pieces that are available in the client but missing in the host
    for (int i = 0; i < clientBitfield.bits.length; i++) {
        if (clientBitfield.bits[i] == (byte) 1 && hostBitfield.bits[i] == (byte) 0) {
            possiblePieces.put(i, clientBitfield.bits[i]);
        }
    }

    // If there are missing pieces, select one randomly
    if (possiblePieces.size() > 0) {
        int member = new Random().nextInt(possiblePieces.size()); // Select a random piece
        Object[] keyArray = possiblePieces.keySet().toArray(); // Convert map keys to an array
        return (int) keyArray[member]; // Return the index of the selected piece
    } else {
        return -1; // Return -1 if no missing pieces are found
    }
    }

public void chokePeerConnection() {
    try {
        // Prepare the CHOKE message to choke the connection
        ByteArrayOutputStream send = new ByteArrayOutputStream(5);
        byte[] length = ByteBuffer.allocate(4).putInt(1).array(); // Message length
        byte[] type = new byte[]{CHOKE}; // Message type (CHOKE)

        send.write(length, 0, 4); // Add length
        send.write(type, 0, 1); // Add type

        client_choked = true; // Mark the client as choked

        out.write(send.toByteArray()); // Send the message

        writeLogMessage("Peer " + hostId + " sent Choke message to Peer " + clientId);
    } catch (IOException ioException) {
        Thread.currentThread().interrupt(); // Handle I/O exceptions
    }
}


        public void unChokePeerConnection() {
            try {
                ByteArrayOutputStream send = new ByteArrayOutputStream(5);
                byte[] length = ByteBuffer.allocate(4).putInt(1).array();
                byte[] type = new byte[]{UNCHOKE};

                send.write(length, 0, 4);
                send.write(type, 0, 1);

                client_choked = false;

                out.write(send.toByteArray());

                writeLogMessage("Peer " + hostId + " sent Unchoke message to Peer " + clientId);
            } catch (IOException ioException) {
                Thread.currentThread().interrupt();
            }
        }

        public void logPreferredPeerSelection() {
            writeLogMessage("Peer " + hostId + " has chosen Peer " + clientId + " as a preferred neighbor.");
        }

        public void logOptimisticUnchokingEvent() {
            writeLogMessage("Peer " + hostId + " optimistically Unchoked Peer " + clientId);
        }

        private int convertBytesToInteger(byte[] payload) {
            return ByteBuffer.wrap(payload).getInt();
        }

        private boolean checkForMissingPieces(byte[] clientBitfield) {
            for (int i = 0; i < clientBitfield.length; i++) {
                if (clientBitfield[i] == (byte) 1 && hostBitfield.bits[i] == (byte) 0) {
                    return true;
                }
            }
            return false;
        }

        public final byte CHOKE = (byte) 0;
        public final byte UNCHOKE = (byte) 1;
        public final byte INTERESTED = (byte) 2;
        public final byte NOT_INTERESTED = (byte) 3;
        public final byte HAVE = (byte) 4;
        public final byte BITFIELD = (byte) 5;
        public final byte REQUEST = (byte) 6;
        public final byte PIECE = (byte) 7;

        public boolean has_payload(byte message_type) {
            switch (message_type) {
                case CHOKE:
                    return false;
                case UNCHOKE:
                    return false;
                case INTERESTED:
                    return false;
                case NOT_INTERESTED:
                    return false;
                case HAVE:
                    return true;
                case BITFIELD:
                    return true;
                case REQUEST:
                    return true;
                case PIECE:
                    return true;

            }
            return false;
        }

        private void writeLogMessage(String toBeLogged) {
    // Print the log message to the console (useful for testing)
    System.out.println(toBeLogged);
    System.out.flush(); // Ensure the message is immediately printed
    logger.info(toBeLogged); // Log the message using the logger
}

}

public class OptimisticUnchokeTask extends TimerTask {
    // Periodically selects a random peer to optimistically unchoke
    public void run() {
        selectAndUnchokeRandomPeer();
    }
}



}
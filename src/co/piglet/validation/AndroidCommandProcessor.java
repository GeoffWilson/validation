package co.piglet.validation;

import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.logging.Level;

import static org.bukkit.Bukkit.getLogger;

public class AndroidCommandProcessor implements Runnable {
    private Socket client;
    private InputStream in;
    private OutputStream out;

    public AndroidCommandProcessor(Socket client) {
        this.client = client;
    }

    @SuppressWarnings("unused")
    private class AndroidCommand {
        private String userName;
        private String password;
        private String targetPlayer;
        private String command;
        private String airship;

        public String authorize() {
            Database db = new Database(getLogger());
            return db.checkAuthorization(userName, password);
        }
    }

    @Override
    public void run() {
        // Read the command from the client
        try {
            in = client.getInputStream();
            out = client.getOutputStream();
            String rawCommand = readResponse();

            getLogger().info(rawCommand);
            Gson gson = new Gson();

            AndroidCommand command = gson.fromJson(rawCommand, AndroidCommand.class);

            getLogger().log(Level.INFO, String.format("C[%s]-S[%s]-T[%s]", command.command, command.userName, command.targetPlayer));
            String sendingPlayer;

            if ((sendingPlayer = command.authorize()).length() > 0) {
                switch (command.command) {
                    case "start":
                        Player player = Bukkit.getPlayerExact(command.targetPlayer);
                        Bukkit.dispatchCommand(player, "airship start " + command.airship);
                        break;

                    case "stop":
                        player = Bukkit.getPlayerExact(command.targetPlayer);
                        Bukkit.dispatchCommand(player, "airship stop " + command.airship);
                        break;

                    case "left":
                        player = Bukkit.getPlayerExact(command.targetPlayer);
                        Bukkit.dispatchCommand(player, "airship left " + command.airship);
                        break;

                    case "right":
                        player = Bukkit.getPlayerExact(command.targetPlayer);
                        Bukkit.dispatchCommand(player, "airship right " + command.airship);
                        break;

                    case "rain":
                        player = Bukkit.getPlayerExact(command.targetPlayer);
                        Bukkit.dispatchCommand(player, "rain");
                        break;

                    case "kill":
                        player = Bukkit.getPlayerExact(command.targetPlayer);
                        player.setHealth(0);
                        break;

                    case "login":
                        // Send OK back to the user
                        Player[] players = Bukkit.getServer().getOnlinePlayers();
                        String[] airships = new String[0];
                        StringBuilder response = new StringBuilder("{");

                        Plugin P = Bukkit.getPluginManager().getPlugin("PigletAirship");

                        try {
                            Method m = P.getClass().getMethod("getAirships");
                            airships = (String[]) m.invoke(P);
                        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
                            e.printStackTrace();
                        }

                        response.append("status : \"OK\", ");
                        response.append("player : \"").append(sendingPlayer).append("\", ");
                        response.append("players : [");
                        for (int i = 0; i < players.length; i++) {
                            response.append("\"").append(players[i].getName()).append("\"");
                            if (i < players.length - 1) response.append(",");
                        }
                        response.append("], ");
                        response.append("airships : [");
                        for (int i = 0; i < airships.length; i++) {
                            response.append("\"").append(airships[i]).append("\"");
                            if (i < airships.length - 1) response.append(",");
                        }
                        response.append("] }");
                        out.write(getCommand(response.toString()));
                        break;
                }
            } else {
                switch (command.command) {
                    case "login":
                        // Send FAIL back to the user
                        out.write(getCommand("FAIL"));
                        break;
                }
            }


        } catch (Exception e) {
            getLogger().warning(e.getMessage());
        }
    }

    private byte[] getCommand(String command) {
        // Put this here so we only do the getBytes call once.
        byte[] commandBytes = command.getBytes();
        // EPP message length is the byte length of the XML command + 4 byte header.
        int lengthMessageToSend = commandBytes.length + 4;
        // Create a ByteBuffer of the correct size for the EPP message
        ByteBuffer byteBuffer = ByteBuffer.allocate(lengthMessageToSend);
        // EPP message order is always BIG_ENDIAN
        byteBuffer.order(ByteOrder.BIG_ENDIAN);
        // Put the 4 byte header at the start of the array
        byteBuffer.putInt(lengthMessageToSend);
        // Add the EPP command after the header
        byteBuffer.put(commandBytes);
        // Return the EPP command as a byte array reading to send via socket.
        return byteBuffer.array();
    }

    private int getMessageLength() throws IOException {
        // EPP header is always 4 bytes (RFC5734).
        byte[] messageLength = new byte[4];

        // Try and read the first 4 byte from the input stream.
        int bytesRead = in.read(messageLength, 0, 4);

        // If we read 4 bytes, then we can return the size of the message.
        if (bytesRead == 4) {
            return ByteBuffer.wrap(messageLength).getInt() - 4;
        }

        // Try again to read the header.
        else {
            bytesRead += in.read(messageLength, bytesRead, 4 - bytesRead);
        }

        // We failed again to read all 4 header bytes, return an exception.
        if (bytesRead < 4) throw new IOException("Failed to get message length");

        // Return the message length if we managed to read the header on the second attempt.
        return ByteBuffer.wrap(messageLength).getInt() - 4;
    }

    private String readResponse() throws IOException, IndexOutOfBoundsException {
        // Get the size of the EPP command to read (does not include 4 byte header).
        int length = this.getMessageLength();

        // Create an array to store the EPP message.
        byte[] response = new byte[length];

        // Try and read the full message length from the input stream.
        int bytesRead = in.read(response, 0, length);

        // If we did not read the whole message then try to read the rest of the message.
        if (bytesRead < length) bytesRead += in.read(response, bytesRead, length - bytesRead);

        // If we still haven't managed to read the whole message then we throw an exception.
        if (bytesRead < length) throw new IOException("Failed to read message");

        // Return the EPP message in string format to the command processor.
        return new String(response, "UTF-8");
    }
}

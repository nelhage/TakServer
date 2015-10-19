package tak;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
/**
 *
 * @author chaitu
 */
public class Client extends Thread {

    Socket socket;
    BufferedReader clientReader;
    PrintWriter clientWriter;
    String name = null;

    static Set<String> names = new HashSet<>();
    static Set<Client> clientConnections = new HashSet<>();

    Game game = null;
    Seek seek = null;

    String placeString = "Game#(\\d+) P ([A-Z])(\\d)( C)?( W)?";
    Pattern placePattern;

    String moveString = "Game#(\\d+) M ([A-Z])(\\d) ([A-Z])(\\d)(( \\d)+)";
    Pattern movePattern;

    String seekString = "Seek (\\d)";
    Pattern seekPattern;

    String acceptSeekString = "Accept (\\d+)";
    Pattern acceptSeekPattern;

    String listString = "List";
    Pattern listPattern;

    String nameString = "Name ([a-zA-Z]{4,10})";
    Pattern namePattern;

    String gameString = "Game#(\\d+) Show";
    Pattern gamePattern;
    
    Client(Socket socket) {
        this.socket = socket;

        placePattern = Pattern.compile(placeString);
        movePattern = Pattern.compile(moveString);
        seekPattern = Pattern.compile(seekString);
        acceptSeekPattern = Pattern.compile(acceptSeekString);
        listPattern = Pattern.compile(listString);
        namePattern = Pattern.compile(nameString);
        gamePattern = Pattern.compile(gameString);

        try {
            clientReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            clientWriter = new PrintWriter(socket.getOutputStream());
        } catch (IOException ex) {
            Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex);
        }
        clientConnections.add(this);
        Seek.registerListener(this);
    }

    void sendOK() {
        send("OK");
    }

    void sendNOK() {
        send("NOK");
    }

    void send(String st) {
        clientWriter.println(st);
        clientWriter.flush();
    }

    void removeSeeks() {
        if (seek != null) {
            Seek.removeSeek(seek.no);
            seek = null;
        }
    }

    void clientQuit() throws IOException {
        clientConnections.remove(this);
        if(game!=null){
            game.clientQuit(this);
        }
        socket.close();

        removeSeeks();
        Seek.unregisterListener(this);
        if (name != null) {
            names.remove(name);
        }
    }

    @Override
    public void run() {
        String temp;
        try {
            send("welcome!");
            send("Name? "+"Enter your name (minimum 4 chars) and only letters");
            while ((temp = clientReader.readLine()) != null && !temp.equals("quit")) {
                temp = temp.trim();
                System.out.println("read "+temp);
                
                Matcher m;

                if (name == null) {
                    if ((m = namePattern.matcher(temp)).find()) {
                        String tname = m.group(1).trim();
                        synchronized(names) {
                            if (!names.contains(tname)) {
                                name = tname;
                                names.add(tname);
                                send("Message Welcome "+name+"!");
                                Seek.sendListTo(this);
                            } else
                                send("Name? "+"Name "+tname+" already taken. "+"Enter your name (minimum 4 chars) and only letters");
                        }
                    } else
                        send("Name? "+"Enter your name (minimum 4 chars) and only letters");
                } else {
                    //List all seeks
                    if ((m = listPattern.matcher(temp)).find()) {
                        sendOK();
                        //send("List " + Seek.seeks.toString());
                        Seek.sendListTo(this);
                    } //Seek a game
                    else if (game==null && (m = seekPattern.matcher(temp)).find()) {
                        if (seek != null) {
                            Seek.removeSeek(seek.no);
                        }
                        seek = Seek.newSeek(this, Integer.parseInt(m.group(1)));
                        sendOK();
                    } //Accept a seek
                    else if (game==null && (m = acceptSeekPattern.matcher(temp)).find()) {
                        Seek sk = Seek.seeks.get(Integer.parseInt(m.group(1)));

                        if (sk != null && game == null && sk.client.game == null && sk!=seek) {
                            removeSeeks();

                            Client otherClient = sk.client;
                            int sz = sk.boardSize;
                            otherClient.removeSeeks();

                            game = new Game(this, otherClient, sz);
                            otherClient.game = game;
                            sendOK();
                            String msg = "Game Start " + game.no +" "+sz+" "+game.white.name+" vs "+game.black.name;
                            send(msg+" "+((game.white==this)?"white":"black"));
                            otherClient.send(msg+" "+((game.white==otherClient)?"white":"black"));
                        } else {
                            sendNOK();
                        }
                    }
                    //Handle place move
                    else if (game != null && (m = placePattern.matcher(temp)).find() && game.no == Integer.parseInt(m.group(1))) {
                        Status st = game.placeMove(this, m.group(2).charAt(0), Integer.parseInt(m.group(3)), m.group(4) != null, m.group(5)!=null);
                        if(st.isOk()){
                            sendOK();
                            //game.white.send(game.toString());
                            //game.black.send(game.toString());
                            Client other = (game.white==this)?game.black:game.white;
                            other.send(temp);
                            
                            if(game.gameState!=game.gameState.NONE){
                                String msg = "Game#"+game.no+" Over ";
                                switch(game.gameState) {
                                    case DRAW: msg+= "1/2-1/2"; break;
                                    case WHITE_ROAD: msg+="R-0"; break;
                                    case BLACK_ROAD: msg+="0-R"; break;
                                    case WHITE_TILE: msg+="F-0"; break;
                                    case BLACK_TILE: msg+="0-F"; break;
                                }
                                send(msg);
                                other.send(msg);
                                game = null;
                                other.game = null;
                            }
                        } else {
                            sendNOK();
                            send("Error:"+st.msg());
                        }
                    }
                    //Handle move move
                    else if (game!=null && (m = movePattern.matcher(temp)).find() && game.no == Integer.parseInt(m.group(1))) {
                        String args[] = m.group(6).split(" ");
                        int argsint[] = new int[args.length-1];
                        for(int i=1;i<args.length;i++)
                            argsint[i-1] = Integer.parseInt(args[i]);
                        Status st = game.moveMove(this, m.group(2).charAt(0), Integer.parseInt(m.group(3)), m.group(4).charAt(0), Integer.parseInt(m.group(5)), argsint);
                        if(st.isOk()){
                            sendOK();
                            //game.white.send(game.toString());
                            //game.black.send(game.toString());
                            Client other = (game.white==this)?game.black:game.white;
                            other.send(temp);
                            if(game.gameState!=game.gameState.NONE){
                                String msg = "Game#"+game.no+" Over ";
                                switch(game.gameState) {
                                    case DRAW: msg+= "1/2-1/2"; break;
                                    case WHITE_ROAD: msg+="R-0"; break;
                                    case BLACK_ROAD: msg+="0-R"; break;
                                    case WHITE_TILE: msg+="F-0"; break;
                                    case BLACK_TILE: msg+="0-F"; break;
                                }
                                send(msg);
                                other.send(msg);
                                game = null;
                                other.game = null;
                            }
                        } else {
                            sendNOK();
                            send("Error:"+st.msg());
                        }
                    }
                    //Show game state
                    else if (game != null && (m=gamePattern.matcher(temp)).find() && game.no == Integer.parseInt(m.group(1))) {
                        sendOK();
                        send(game.toString());
                    }
                    //Undefined
                    else {
                        sendNOK();
                    }
                }
            }

            clientQuit();
            System.out.println("disconnected");
        } catch (IOException ex) {
            Logger.getLogger(Client.class.getName()).log(Level.SEVERE, null, ex);
        }
    }
}

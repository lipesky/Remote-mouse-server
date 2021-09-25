import java.awt.AWTException;
import java.awt.Component;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.InputEvent;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.awt.image.BufferedImage;
import java.awt.Rectangle;
import javax.imageio.ImageIO;

public class RemoteMouseApplication {

	public static List<RClient> clients = new ArrayList<>();
	public static Thread screenThread;
	public static ScreenServer screenServer;
	public static DatagramSocket udpSocket;

	public static void main(String[] args) throws InterruptedException, IllegalAccessException,ClassNotFoundException {
		try {
			System.setProperty("java.awt.headless", "false");
			
			RemoteMouseApplication.udpSocket = new DatagramSocket(9998);
			
			final Robot robot = new Robot(MouseInfo.getPointerInfo().getDevice());

			final ServerSocket tcpServer = new ServerSocket(9999);

			System.out.println("Listening");
			while(true){		
				final Socket conn = tcpServer.accept();
				System.out.println("connection received");
				final Thread conHandler = new Thread(new ConnectionHandler(conn, robot));
				conHandler.start();
				// RemoteMouseApplication.clients.add(new RClient(conHandler, conn));
			}			
		} catch (IOException e) {
			e.printStackTrace();
		} catch(AWTException e) {
			e.printStackTrace();
		}	
	}

	public static synchronized void verifyUdp(){
		List<RClient> receivingScreenClients = RemoteMouseApplication.getClientsForScreen();
		if(receivingScreenClients.size() == 0){
			if(RemoteMouseApplication.screenThread != null){
				RemoteMouseApplication.screenThread.stop();
			}
			RemoteMouseApplication.screenThread = null;
		}else if(RemoteMouseApplication.screenThread == null || !RemoteMouseApplication.screenThread.isAlive()){
			RemoteMouseApplication.screenServer = new ScreenServer(
				RemoteMouseApplication.udpSocket,
				receivingScreenClients
			);
			RemoteMouseApplication.screenThread = new Thread(RemoteMouseApplication.screenServer);
			RemoteMouseApplication.screenThread.start();
		}else{
			RemoteMouseApplication.screenServer.setDirtyClients(true);
		}
	}

	public static synchronized void addClient(RClient client){
		RemoteMouseApplication.clients.add(client);
		RemoteMouseApplication.verifyUdp();
	}

	public static synchronized void removeClient(String ip){
		RemoteMouseApplication.clients.removeIf(x -> x.tcpSocket.getInetAddress().getHostName().contains(ip));
		RemoteMouseApplication.verifyUdp();
		System.out.println("client disconnected");
	}

	public static synchronized void removeClient(Thread thread){
		RemoteMouseApplication.clients.removeIf(x -> x.tcpThread.equals(thread));
		RemoteMouseApplication.verifyUdp();
		System.out.println("client disconnected");
	}

	public static synchronized void removeClient(Socket tcpSocket){
		RemoteMouseApplication.clients.removeIf(x -> x.tcpSocket.equals(tcpSocket));
		RemoteMouseApplication.verifyUdp();
		System.out.println("client disconnected");
	}

	public static synchronized void removeClient(RClient client){
		RemoteMouseApplication.clients.removeIf(x -> x.equals(client));
		RemoteMouseApplication.verifyUdp();
		System.out.println("client disconnected");
	}
	public static synchronized List<RClient> getClientsForScreen(){
		return RemoteMouseApplication.clients.stream()
			.filter(client -> client.receiveScreen)
			.collect(Collectors.toList());
	}
}

class RClient{
	public Thread tcpThread;
	public Socket tcpSocket;
	public int udpPortScreen;
	public boolean receiveScreen;

	public RClient(){};

	public RClient(Thread tcpThread){
		this.tcpThread = tcpThread;
	}

	public RClient(Thread tcpThread, Socket tcpSocket){
		this.tcpThread = tcpThread;
		this.tcpSocket = tcpSocket;
	};

	public RClient(Thread tcpThread, Socket tcpSocket, boolean receiveScreen){
		this.tcpThread = tcpThread;
		this.tcpSocket = tcpSocket;
		this.receiveScreen = receiveScreen;
	};
}

class ConnectionHandler implements Runnable{

	private Socket conn;
	private Robot robot;
	private RClient client;

	public ConnectionHandler(Socket conn, Robot robot){
		this.conn = conn;
		this.robot = robot;
		this.client = new RClient(Thread.currentThread(), this.conn, false);
	}

	public void run(){
		try {
			RemoteMouseApplication.addClient(this.client);
			final PrintWriter writer = new PrintWriter(this.conn.getOutputStream());	
			final BufferedReader reader = new BufferedReader(new InputStreamReader(this.conn.getInputStream()));				
			while(this.conn.isConnected()) {
				final String message = reader.readLine();
				if(message != null){
					onReceiveMessage(message, this.robot, writer);
				}					
			}	
		} catch (IOException e) {
			e.printStackTrace();
		}finally{
			RemoteMouseApplication.removeClient(this.client);
		}
	}

	private void onReceiveMessage(String message, Robot robot, PrintWriter writer)  {
		final String[] parts = message.split(";");
		final String cmd = parts[0];
		final String data = parts.length > 1 ? parts[1] : "";
		System.out.println("["+this.conn.getInetAddress()+"]: "+message);
		switch (cmd) {
			case "move":
				String[] coordinates = data.split(",");
				robot.mouseMove(Integer.parseInt(coordinates[0]), Integer.parseInt(coordinates[1]));
				break;
			case "lclick":
				robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
				robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
				break;
			case "rclick":
				robot.mousePress(InputEvent.BUTTON3_DOWN_MASK);
				robot.mouseRelease(InputEvent.BUTTON3_DOWN_MASK);
				break;	
			case "keyPress":
				robot.keyPress(Integer.valueOf(data, 16));
				break;
			case "keyRelease":
				robot.keyRelease(Integer.valueOf(data, 16));
				break;
			case "mousePress":
				robot.mousePress(Integer.valueOf(data, 16));
				break;
			case "mouseRelease":
				robot.mouseRelease(Integer.valueOf(data, 16));
				break;
			case "scrollHorizontal":
				//Not Supported
				System.out.println("scrollHorizontal - Not implemented");
				break;
			case "scrollVertical":
				robot.mouseWheel(Integer.parseInt(data));
				break;
			case "requestMouseOffset":
				sendPointerOffset(writer);
				break;
			case "subscribeToScreen":
				//subscribeToScreen(Integer.parseInt(data));
				break;
			case "unSubscribeToScreen":
				unSubscribeToScreen();
				break;
			default:
				System.out.println(cmd+" - Not implemented");
		}
	}

	private void subscribeToScreen(int port){
		this.client.receiveScreen = true;
		this.client.udpPortScreen = port;
		RemoteMouseApplication.verifyUdp();
	}

	private void unSubscribeToScreen(){
		this.client.receiveScreen = false;
		RemoteMouseApplication.verifyUdp();
	}

	private void sendPointerOffset(PrintWriter writer) {		
		final Point offset = MouseInfo.getPointerInfo().getLocation();
		writer.write("{\"message\" : \"pointerOffset\", \"value\": \""+offset.x+","+ offset.y+"\"}");
		writer.flush();
	}
}

class ScreenServer implements Runnable{

	private DatagramSocket server;
	private int maxFps;
	private List<RClient> clients;
	private Robot robot;
	private volatile boolean dirtyClients;

	public ScreenServer(DatagramSocket server){
		this.server = server;
		this.maxFps = 60;
		this.clients = new ArrayList<RClient>();
		this.dirtyClients = false;
		this.initRobot();
	}

	public ScreenServer(DatagramSocket server, List<RClient> clients){
		this.server = server;
		this.maxFps = 60;
		this.clients = clients;
		this.dirtyClients = false;
		this.initRobot();
	}

	public synchronized void setDirtyClients(boolean value){
		this.dirtyClients = value;
	}

	public void run(){
		try{			
			while(true){
				if(this.dirtyClients){
					this.refreshAddresses();
				}
				if(this.clients.size() > 0){
					final Chunker imgChunks = new Chunker(getScreenRect());
					byte[] buffer = imgChunks.next();
					while(buffer != null){

						for(final RClient client: this.clients){																						
							DatagramPacket datagram = new DatagramPacket(
								buffer, 
								buffer.length,
								client.tcpSocket.getInetAddress(),
								client.udpPortScreen
							);
							this.server.send(datagram);
						}

						buffer = imgChunks.next();
					}
				}
				Thread.sleep((int) 1000/this.maxFps );
				//Thread.sleep(10000);
			}
		}catch(Exception e){
			e.printStackTrace();
		}finally{
			this.server.close();
			System.out.println("Screen Server finalized");			
		}
	}

	private void initRobot(){
		try{
			this.robot = new Robot(MouseInfo.getPointerInfo().getDevice());
		}catch(Exception e){
			e.printStackTrace();
			this.server.close();
			System.out.println("Failed to initialize Robot in ScreenServer.");
			System.exit(0);
		}
	}

	private byte[] getScreenRect(){		
		final int maxWidth = 800;//this.getMaxWidth();
		final int maxHeight = 800;//this.getMaxHeight();

		final Point cursor = MouseInfo.getPointerInfo().getLocation();
		final BufferedImage img = this.robot.createScreenCapture(
			new Rectangle(
				(int)(cursor.x - (maxWidth / 2)),
				(int)(cursor.y - (maxHeight / 2)),
				maxWidth,
				maxHeight
			)
		);
		
		final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		try{
			ImageIO.write(img, "jpg", byteArrayOutputStream);
			return byteArrayOutputStream.toByteArray();
		}catch(IOException e){
			e.printStackTrace();
			return new byte[0];
		}		
	}

	private void refreshAddresses(){
		this.clients = RemoteMouseApplication.getClientsForScreen();
		this.dirtyClients = false;
	}
}

class Chunker{

	private byte[] data;
	private int offset = 0;
	private int slice_size = 481;
	private int chunk_size = 489;
	private int index_size = 8;
	private int index = 0;

	public Chunker(byte[] data){
		this.data = data;
	}

	public Chunker(byte[] data, int chunk_size){
		this.data = data;
		this.chunk_size = chunk_size;
		this.slice_size = chunk_size - 8;
	}

	public Chunker(byte[] data, int chunk_size, int index_size){
		this.data = data;
		this.chunk_size = chunk_size;
		this.slice_size = chunk_size - index_size;
	}

	public int getChunkSize(){
		return this.chunk_size;
	}

	private byte[] getIndex(){
		byte[] b = ByteBuffer.allocate(this.index_size).putInt(this.index).array();
		this.index++;
		return b;
	}
	
	private byte[] concat(byte[] arr1, byte[] arr2){
		byte[] result = new byte[arr1.length + arr2.length];
		System.arraycopy(arr1, 0, result, 0, arr1.length);
		System.arraycopy(arr2, 0, result, arr1.length, arr2.length);
		return result;
	}

	public byte[] next(){
		if(this.data.length == offset){
			return null;
		}else if(this.data.length < offset + slice_size){
			byte[] slice = Arrays.copyOfRange(this.data, offset, this.data.length);
			offset = this.data.length;			
			return concat(this.getIndex(), slice);
		}
		byte[] slice = Arrays.copyOfRange(this.data, offset, offset + slice_size);
		offset = offset + slice_size;
		return concat(this.getIndex(), slice);
	}
}
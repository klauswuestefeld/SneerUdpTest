/*
 *  does NOT, EVER change the external port of a local UDP socket that you open and 
 *  bind locally to a local port (the external port is first allocated, if your router
 *  is a normal one, when you send out the first UDP packet, namely, to the "stun 
 *  server" whose DNS address is hardcoded here in the app ("dynamic.sneer.me") ). 
 * 
 * If the external port changes for the (single) UDP socket this opens, you have
 *  to restart the app. We are hoping it does not change because, for one thing, we 
 *  don't ever stop sending packets. Every two seconds there's one UDP packet headed 
 *  out to somewhere: either the STUN server (at first) or some valid or invalid peer 
 *  address (hopefully if you're slow to type a peer address it will still keep the 
 *  NAT external port allocation for your own router alive... or maybe not. So type 
 *  your friend's IP address in there _faster_! :-) )
 *  
 * When this app turns on, your IP should show up, as well as the IP of your friend in 
 *  his instance of the app. You guys exchange your IPs by Skype chat and fill in each 
 *  other's IP addresses in the "peer address" EditBox, and voila, you should be directly
 *  connected.
 *  
 * And yes, since you have a centralized STUN server there, that lazy ass server should 
 *  have done the address exchange for you :-) with a little bit of additional help (or 
 *  not ... I say FULL MESH between ALL the thousands of peers running this hello world 
 *  app at any given time in the world!!11!!)
 * 
 */

package sneer.udpspike;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;

class NetworkThread extends Thread {

	// new thread: tell us where the UI is so we can remember it ...
	Activity myActivity;
	NetworkThread(Activity act) {
		myActivity = act;
	}

	//----------------------------------------------------
	// state of the network (main, really) thread
	//----------------------------------------------------

	boolean terminate = false; // whether the UI/AndroidOS wants us to commit suicide
	
	// retarded polling(antipattern -- we bypassed the event-driven nature of Android) 
	//   -induced duplication of the state that's already encoded in the UI controls.
	// this stores a snapshot of what the network thread (the real boss) wants to
	//   set at the UI and what it knows is the latest state from the UI (latest from
	//   the last 2-second lagged poll, that is).
	long disappointments = 0; // we (network thread) own this
	long joys = 0;         // we (network thread) own this
	String youraddr = "";  // we (network thread) own this
	String status = "";    // we (network thread) own this
	String peermsg = "";   // we (network thread) own this 
	String peeraddr = "";  // ui thread owns this, network thread shadows it
					       // because the user types in the peer addr
	String yourmsg = ""; // ui thread owns this, network thread shadows it
	                     // because the user types in what he wants to say

	//----------------------------------------------------
	// code for the network (main, really) thread
	//----------------------------------------------------

	// a "function" called from the network thread that does ALL of the touching base 
	//    with the UI thread.
	// this polling is retarded. don't ever do this outside of hello-world-type apps.
	class UIThreadUpdater implements Runnable {
		
		Activity myActivity;
		UIThreadUpdater(Activity act) {
			myActivity = act;
		}
		
		// we call this thing every two seconds
		// (EVERYTHING happens every two seconds in this app :-)
		// batch update a bunch of crap in the UI
		// update everything AND also read everything in, for good measure
		public void run() {
			
			//------------------------------------------------------------
			// push what WE (the networking/looping thread) know into 
			//   the UI thread/eventsystem
			//------------------------------------------------------------
			
			(( TextView )myActivity.findViewById(R.id.textViewErrorCount) ).setText( Long.valueOf(disappointments).toString() );
			(( TextView )myActivity.findViewById(R.id.textViewIncomingUDPCount) ).setText( Long.valueOf(joys).toString() );
			
			// already formatted -- this is just chucking a string into the UI
			(( TextView )myActivity.findViewById(R.id.textViewYourAddr) ).setText( youraddr );
			
			(( TextView )myActivity.findViewById(R.id.textViewStatus) ).setText( status );
			(( TextView )myActivity.findViewById(R.id.textViewPeerMessage) ).setText( peermsg );

			//------------------------------------------------------------
			// slurp what THE UI thread/eventsystem decides (user-typed) 
			//   into our lagged/polled cache view here
			//------------------------------------------------------------
			
			yourmsg = ( ( EditText )myActivity.findViewById( R.id.content_et ) ).getText().toString();

			// this is not split, findaddrbyname'd etc. just the raw editbox text:
			peeraddr = ( ( AutoCompleteTextView )myActivity.findViewById( R.id.server_actv ) ).getText().toString();
		}
	}

	public void setTerminate() {
		terminate = true;
	}
	
	public void run() {

		// this is the main state-machine switch (the two "main" modes the network thread runs in)
		boolean resolvedSelfIP = false; // set to true when we are finished successfully with
				                          // our business between the local UDP socket and 
		                                  // the STUN server, and are ready to play peer poking.
										  // setting this to TRUE is an one-way street.

		// last time a packet seen from a peer
		long lastTimeSeen = 0;  // serves as "never"
				
		// just a static global function "run()" in disguise
		// juggling the myActivity around so we don't get accused
		//   of using a global static var for myActivity. 
		UIThreadUpdater uiThreadUpdater = new UIThreadUpdater(myActivity);
		
		// open teh socket
		DatagramSocket udpSocket;			
		try {
			udpSocket = new DatagramSocket();
		} catch (SocketException e) {
			throw new RuntimeException(e); // terrible...
		}
		try {
			udpSocket.setSoTimeout(2000);
		} catch (SocketException e1) {

			status = "Cannot set socket option.";
			myActivity.runOnUiThread( uiThreadUpdater ); // bleh
			
			udpSocket.close();
			udpSocket = null;
			throw new RuntimeException("Error socket option.");
		}
				
		// the network thread loops doing stuff
		// whatever path it takes, it will do at least one 2-second-blocking receive
		while (! terminate) {
			
			// what is the main mode? stun or peer mode?
			if (! resolvedSelfIP) {

				// -------------------------------------------------------------------
				// network thread in work-with-stun-server mode
				// all sent packets are to the hardcoded stunserver
				// all received packets assumed to be from the hardcoded stunserver
				// -------------------------------------------------------------------

				status = "Resolving self IP:PORT and NAT type...";
				myActivity.runOnUiThread( uiThreadUpdater ); // bleh

				// copied previous logic: send two packets, wait for exactly
				//  two in two seconds, treat partial packet loss as not-what-we-wanted
				//  and keep trying.
				// if exactly same message is received from both, successfully, then
				//  we know what our IP address is and we print it to self (it's the reply)

				try {
					final InetAddress serverIPAddress1 = InetAddress.getByName("dynamic.sneer.me");

					final DatagramPacket sendPacket1 = new DatagramPacket(new byte[0], 0, serverIPAddress1, 5556);
					final DatagramPacket sendPacket2 = new DatagramPacket(new byte[0], 0, serverIPAddress1, 5557);

					udpSocket.send( sendPacket1 );
					udpSocket.send( sendPacket2 );

					final byte[] receiveData1 = new byte[1024];
					final DatagramPacket receivePacket1 = new DatagramPacket(receiveData1, receiveData1.length);
					try { 
						udpSocket.receive(receivePacket1); 
					} catch (SocketTimeoutException e) { 
						// don't care; the string will remain empty
					}

					final byte[] receiveData2 = new byte[1024];
					final DatagramPacket receivePacket2 = new DatagramPacket(receiveData2, receiveData2.length);
					try { 
						udpSocket.receive(receivePacket2); 
					} catch (SocketTimeoutException e) {
						// don't care; the string will remain empty						
					}

					final String incomingData1 = new String(receivePacket1.getData(), "UTF-8");
					final String incomingData2 = new String(receivePacket2.getData(), "UTF-8");

					if (incomingData1.equals(incomingData2)) {
						joys++;
						status = "Got self IP:port & 'nice NAT' confirm!";
						youraddr = incomingData1;
						myActivity.runOnUiThread( uiThreadUpdater ); // bleh

						resolvedSelfIP = true;

					} else if (incomingData1.length() > 0 && incomingData2.length() > 0) {
						disappointments++;
						status = "It seems your NAT is fascist. Retrying...";
						myActivity.runOnUiThread( uiThreadUpdater ); // bleh
					} else {
						disappointments++;
						status = "It seems packets were lost. Retrying...";
						myActivity.runOnUiThread( uiThreadUpdater ); // bleh
					}
					
				} catch (Exception e) {
					status = e.getClass() + " ... Retrying ...";
					disappointments++;
					myActivity.runOnUiThread( uiThreadUpdater ); // bleh
				}
				
				// wait so any messages can be read; also if it was successful
				//  we'd have to wait for the user to type the peer ip address
				//  as well...
				try {
					Thread.sleep(2000);
				} catch	 (InterruptedException e) {
					// do I care? not really.
					// they want us to re-test the outer terminate-loop ...
				}
								
			} else {

				// -------------------------------------------------------------------
				// network thread in work-with-peers mode
				// all sent packets are to a potential peer
				// all received packets assumed from a peer
				// -------------------------------------------------------------------
				
				// try to translate peer address to target IP address
				final String[] tokens = peeraddr.split( ":" );
				
				InetAddress peerIPAddress;
				try {

					if (tokens.length != 2) {
						throw new UnknownHostException("Bad host name formatting.");
					}

					final String ip = tokens[ 0 ];
					final int peerPort = Integer.parseInt( tokens[ 1 ] );
					peerIPAddress = InetAddress.getByName( ip );
					
					boolean considerConnected = ((System.currentTimeMillis() - lastTimeSeen) < 7000); 
					
					if (considerConnected) { 
						status = "Connected: " + peeraddr;
					} else {
						status = "Trying: " + peeraddr;
						
						// If we're trying now (or at first), then by 
						//  definition the remote peer is not saying anything.
						// Whatever we saw as his state is now gone.
						peermsg = "";
					}
					myActivity.runOnUiThread( uiThreadUpdater ); // bleh
					
					try {

						// try to send yourmsg to that IP:port
						final byte[] sendData = yourmsg.getBytes( "UTF-8" );
						final DatagramPacket sendPacket = new DatagramPacket(sendData, 0, peerIPAddress, peerPort);
						udpSocket.send( sendPacket );

						// receive any packets (peermsg) if we have a path to that peer
						//  and that peer is on and sending stuff to our correct address...

						final byte[] receiveData = new byte[1024];
						final DatagramPacket receivePacket = new DatagramPacket(receiveData, receiveData.length);
						try { 
							udpSocket.receive(receivePacket);
							
							// got one -- returned from receive w/o the throw
							joys++;
							lastTimeSeen = System.currentTimeMillis();

							peermsg = new String(receivePacket.getData(), "UTF-8");

						} catch (SocketTimeoutException e) {
							// didn't get one
							// we'll count this as a disappointment only if it has
							//  been long since the last one seen (or never seen one)
							if (! considerConnected) {
								disappointments++;
							}								
						}
					} catch (Exception e) {
						status = e.getClass() + " ... Retrying ...";
						disappointments++;
						myActivity.runOnUiThread( uiThreadUpdater ); // bleh

						// wait so the message above can be read
						try {
							Thread.sleep(2000);
						} catch	 (InterruptedException ie) {
							// do I care? not really.
							// they want us to re-test the outer terminate-loop ...
						}
					}
					
				} catch (UnknownHostException e1) {
					
					status = "Please enter a valid peer address.";
					disappointments++;
					myActivity.runOnUiThread( uiThreadUpdater ); // bleh
					
					// wait so the message above can be read
					try {
						Thread.sleep(2000);
					} catch	 (InterruptedException e) {
						// do I care? not really.
						// they want us to re-test the outer terminate-loop ...
					}
				}
			}

			//... and here we go again.
			// we spent at least 2 seconds stuck in the above mess before 
			//   looping again, so we can read status messages.
		}
		
		// close teh socket
		udpSocket.close();
		udpSocket = null;
	}
}


public class MainActivity extends Activity {

	NetworkThread thr;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_main );

		(( TextView )findViewById(R.id.textViewStatus) ).setText("Starting network thread.");

		// create thread that actually does stuff
		thr = new NetworkThread(this);
		thr.start();
		
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Started network thread.");
	}
	
	@Override
	protected void onStart() {
		super.onStart();
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Started.");
	}
	@Override
	protected void onRestart() {
		super.onRestart();
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Restarted.");
	}
	@Override
	protected void onPause() {
		super.onPause();
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Paused.");
	}
	@Override
	protected void onResume() {
		super.onResume();
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Resumed.");
	}
	@Override
	protected void onStop() {
		super.onStop();
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Stopped.");		
	}
	
	@Override
	protected void onDestroy() {
		super.onDestroy();
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Destroying...");
		thr.setTerminate();
		try {
			thr.join(4000);
		} catch (InterruptedException e) {
			//e.printStackTrace();  //don't care
		}
		if (thr.isAlive()) {
			thr.interrupt();
		}
		thr = null;
		(( TextView )findViewById(R.id.textViewStatus) ).setText("Destroyed.");
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate( R.menu.activity_main, menu );
		return true;
	}

}

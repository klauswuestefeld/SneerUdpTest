
package sneer.udpspike;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;

import android.app.Activity;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.Menu;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends Activity {

	DatagramSocket udpSocket;

	@Override
	protected void onCreate(final Bundle savedInstanceState) {
		super.onCreate( savedInstanceState );
		setContentView( R.layout.activity_main );
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate( R.menu.activity_main, menu );
		return true;
	}

	public void onSendButtonClick(final View view) {
		findViewById( R.id.send_bt ).setEnabled( false );

		final SendTask sendTask = new SendTask();
		sendTask.execute( ( Void )null );
	}

	private class SendTask extends AsyncTask<Void, String, AsyncTaskResult<Void>> {

		
		@Override
		protected AsyncTaskResult<Void> doInBackground(final Void... params) {


			try {

				final String[] tokens = ( ( AutoCompleteTextView )findViewById( R.id.server_actv ) ).getText().toString().split( ":" );
				final String ip = tokens[ 0 ];
				final int port = Integer.parseInt( tokens[ 1 ] );
				final String msg = ( ( EditText )findViewById( R.id.content_et ) ).getText().toString();

				final InetAddress IPAddress = InetAddress.getByName( ip );

				if (udpSocket == null) udpSocket = openUdpSocket();
				
				send( IPAddress, port, msg);
				send( IPAddress, ( port + 1 ), msg);

				receive( 1 );
				receive( 2 );

//				udpSocket.close();

			} catch(final Exception e) {
				return new AsyncTaskResult<Void>( e );
			}

			return new AsyncTaskResult<Void>( ( Void )null );
		}

		private DatagramSocket openUdpSocket() {
			try {
				return new DatagramSocket();
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}
		}

		@Override
		protected void onProgressUpdate(final String... values) {
			for(final String v : values) {
				( ( TextView )findViewById( R.id.log_tv ) ).append( v + "\n" );
			}
		}

		@Override
		protected void onPostExecute(final AsyncTaskResult<Void> result) {
			findViewById( R.id.send_bt ).setEnabled( true );

			if(result.hasError())
				onProgressUpdate("Error", toString(result.error));
		}

		private void receive(int attempt) throws Exception {
			final byte[] receiveData = new byte[ 1024 ];
			final DatagramPacket receivePacket = new DatagramPacket( receiveData, receiveData.length );
			udpSocket.setSoTimeout(2000);
			try {
				udpSocket.receive(receivePacket);
			} catch (SocketTimeoutException e) {
				publishProgress( "TIMEOUT" );
				return;
			}
			final String echo = new String( receivePacket.getData(), "UTF-8" );
			publishProgress( "ECHO " + attempt + ":" + echo );
		}

		private void send(final InetAddress IPAddress, final int port, final String msg) throws Exception {
			final byte[] sendData = msg.getBytes( "UTF-8" );
			final DatagramPacket sendPacket = new DatagramPacket( sendData, sendData.length, IPAddress, port );
			udpSocket.send( sendPacket );
		}

		private String toString(final Exception e) {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			final PrintWriter printWriter = new PrintWriter( bytes );
			e.printStackTrace( printWriter );
			printWriter.flush();
			return new String( bytes.toByteArray() );
		}

	}

}

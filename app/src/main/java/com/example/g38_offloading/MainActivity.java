package com.example.g38_offloading;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    Button listen;
    TextView myLocation,connStatus,batteryStatus, info;

    //variables for location
    FusedLocationProviderClient mFusedLocationClient;
    int PERMISSION_ID = 44;
    String myLatitude, myLongitude;
    String master_name;
    private FusedLocationProviderClient client;

    //available device information like socket, device name and availability status
    BluetoothAdapter bluetoothAdapter;
    int available_devices=0; //maintained at master to keep track of number of available slaves that were free

    ArrayList<BluetoothSocket> connected_socket=new ArrayList<BluetoothSocket>(); //maintains the list of connected sockets

    SendReceive sendReceive;

    int rejectMsgFlag=0; //maintained at slave if it has rejected offloading

    public BluetoothServerSocket serverSocket;

    //connections status checks

    static final int STATE_LISTENING =1;
    static final int STATE_CONNECTING=2;
    static final int STATE_CONNECTED=3;
    static final int STATE_CONNECTION_FAILED=4;
    static final int STATE_MESSAGE_RECEIVED=5;
    static final int STATE_BATTERY_LOW=6;
    static final int STATE_DISCONNECTED=7;
    static final int STATE_DENIED =8;
    int REQUEST_ENABLE_BLUETOOTH=1;

    Map<BluetoothSocket,ArrayList<String>> connection_status=new HashMap<BluetoothSocket, ArrayList<String>>(); //maintained at master to keep track of whether the slave was busy and free
    Map<String,Integer> battery_final=new HashMap<String,Integer>(); //maintained at master to store batter level status of slaves once offloading is done
    Map<String,Integer> battery_initial=new HashMap<String,Integer>(); //maintained at master to store the battery level status of slaves when they are connected
    Map<Integer,Long> row_sent_time=new HashMap<Integer, Long>(); //maintained at master to check at what time row was sent to slave
    ArrayList<Integer> row_check=new ArrayList<Integer>(); //maintained at master to check what all rows are yet to be sent

    Map<Integer,String> outputRows_check=new HashMap<Integer, String>(); //maintained at master to check what all rows were received from slave

    private static final String APP_NAME= "MobOffloading";
    private static final UUID MY_UUID=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); //UUID which helps in connection establishment between master and slave

    int battery_level=0; //maintains the battery level of the slave
    int battery_threshold=40; //battery threshold that should be maintained for the device to do offloading

    String device_name;

    String display_msg=""; //maintains what message to be displayed in message box

    String device_loc ="";  //maintains latitude and longitude information
    int battery_check_count=1; //maintained at slave to check when it has clicked reject offloading and trying to accept offloading again whether offloading was already completed or not at master
    int temp_batterycheck;

    int battery_level_start=0;

    DataConversionSerial dataSerializer;
    serialEncoder response_temp;
    private Handler offloadingHandler=new Handler();  //handles the connection status and messages received
    String connected_device;

    //below broadcast Receiver is getting the battery level of the device. It will always show the latest value of the batter level
    private BroadcastReceiver mBatInfoReceiver= new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            battery_level=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,0);
            batteryStatus.setText("Battery level: "+String.valueOf(battery_level)+"%");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)  {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

//        Bar and Launcher Icon
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setIcon(R.mipmap.ic_launcher);

        batteryStatus=(TextView) findViewById(R.id.batteryStatus);
        connStatus = (TextView) findViewById(R.id.connStatus);
        myLocation = (TextView) findViewById(R.id.myLocation);
        info = (TextView) findViewById(R.id.infoView);
        info.setMovementMethod(ScrollingMovementMethod.getInstance());
        listen = (Button) findViewById(R.id.listen);

        dataSerializer = new DataConversionSerial();

        // show location
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        // method to get the location
        getLastLocation();

        // set battery level
        this.registerReceiver(this.mBatInfoReceiver,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        device_name=BluetoothAdapter.getDefaultAdapter().getName(); //get the bluetooth name of the device

        //Below will enable the bluetooth in the device in case it's disables
        bluetoothAdapter=BluetoothAdapter.getDefaultAdapter();

        if(!bluetoothAdapter.isEnabled())
        {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent,REQUEST_ENABLE_BLUETOOTH);
        }
        listen.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v) {
                Intent intent_available=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                intent_available.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,500);
                startActivity(intent_available);
                slaveListen slavelisten=new slaveListen();
                slavelisten.start();
            }
        });
    }

    private class slaveListen extends Thread
    {
        public slaveListen()
        {
            try
            {
                if(battery_level>=battery_threshold)
                {
                    serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }

        public void run()
        {
            BluetoothSocket socket=null;

            if(battery_level<battery_threshold)
            {
                Message message=Message.obtain();
                message.what=STATE_BATTERY_LOW;
                handler.sendMessage(message);
            }
            //will keep on looking until it's connected to any device with the same UUID
            while(socket==null && battery_level>=battery_threshold)
            {
                try
                {
                    System.out.println("Inside sever");
                    Message message=Message.obtain();
                    message.what=STATE_LISTENING;
                    handler.sendMessage(message);

                    socket=serverSocket.accept();

                }
                catch (IOException e)
                {
                    e.printStackTrace();
                    Message message=Message.obtain();
                    message.what=STATE_CONNECTION_FAILED;
                    handler.sendMessage(message);
                }

                if(socket!=null)
                {
                    connected_socket.add(socket);
                    master_name=socket.getRemoteDevice().getName();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setMessage( master_name+ " Master wants to offload matrix multiplcation, monitor location and battery level ?").setPositiveButton("Accept", dialogClickListener)
                                    .setNegativeButton("Deny", dialogClickListener).show();
                        }
                    });
                    socket = null;
                }
            }
        }
    }

    private void accept_master(BluetoothSocket socket) {
        System.out.println("Inside sever");
        Message message = Message.obtain();
        message.what = STATE_CONNECTED;
        handler.sendMessage(message);


//        connected_socket.add(socket);

        if (!connection_status.containsKey(socket)) {
            ArrayList<String> temp = new ArrayList<String>();
            temp.add(socket.getRemoteDevice().getName());
            temp.add("free");
            available_devices++;
            connection_status.put(socket, temp);
        }
        sendReceive = new SendReceive(socket);
        sendReceive.start();

        try {
            //Toast.makeText(getApplicationContext(),"check"+location,Toast.LENGTH_LONG).show();
            if (device_loc != null && !device_loc.isEmpty()) {
                sendReceive.write(dataSerializer.objectToByteArray(device_name + ":Battery Level:" + Integer.toString(battery_level) + ":Location:" + device_loc));
            } else {
                sendReceive.write(dataSerializer.objectToByteArray(device_name + ":Battery Level:" + Integer.toString(battery_level)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // dialogue box fo4r accepting offloading
    DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            switch (which){
                case DialogInterface.BUTTON_POSITIVE:
                    //Yes button clicked
                    accept_master(connected_socket.get(0));
                    break;

                case DialogInterface.BUTTON_NEGATIVE:
                    //No button clicked
                    Message message=Message.obtain();
                    message.what=STATE_DENIED;
                    handler.sendMessage(message);
                     connected_socket.remove(0);
                    break;
            }
        }
    };
    //This handler will handle what to do based on the msg received and sets the message in status according to it
    Handler handler=new Handler(new Handler.Callback()
    {
        @Override
        public boolean handleMessage(@NonNull Message msg)
        {
            switch(msg.what)
            {
                case STATE_LISTENING:
                    connStatus.setText("Listening");
                    break;
                case STATE_CONNECTING:
                    connStatus.setText("Connecting");
                    break;
                case STATE_CONNECTED:
                    connStatus.setText("Connected to " +master_name);
                    break;
                case STATE_CONNECTION_FAILED:
                    connStatus.setText("Connection Failed");
                    break;
                case STATE_DENIED:
                    connStatus.setText("Connection Denied");
                    break;
                case STATE_DISCONNECTED:
                    connStatus.setText("Disconnected");
                    break;

                //this functionality will be called if slave has received message from master or viceversa
                case STATE_MESSAGE_RECEIVED:
                    //If the device is acting as server
                    try
                    {
                        byte[] readBuff = (byte[]) msg.obj;

                        Object o=dataSerializer.byteArrayToObject(readBuff);
                        //If the object received from client is of serialDecoder then slave has received work to act on masters request
                        if(o instanceof serialDecoder)
                        {
                            serialDecoder tempMsg = (serialDecoder) o;

                            display_msg += "Received row " + tempMsg.getRow() + " from Master" + "\n";
                            //calucate the matrix multiplication

                            info.setText(display_msg.trim());
                            for (BluetoothSocket socket : connected_socket)
                            {
                                if (socket != null)
                                {
                                    int[] result_output = new int[tempMsg.getB()[0].length];
                                    for (int i = 0; i < tempMsg.getB()[0].length; i++) {
                                        int sum = 0;
                                        for (int j = 0; j < tempMsg.getA().length; j++) {
                                            sum += tempMsg.getA()[j] * tempMsg.getB()[j][i];

                                        }
                                        result_output[i] = sum;
                                        System.out.println("i" + i);
                                        // for(int )
                                        //System.out.println(result_output[i]);
                                    }
                                    //Create a response of object of type serialEncoder
                                    serialEncoder response = new serialEncoder(result_output, tempMsg.getRow(), device_name);
                                    //If it hasn't rejcted offloading send the result row  and row number to master
                                    if (rejectMsgFlag == 0 && battery_level>=battery_threshold) {
                                        sendReceive.write(dataSerializer.objectToByteArray(response));
                                    }
                                    //otherwise temporarily store the row result in case it has accepted offloading and has to send row result
                                    else
                                    {
                                        response_temp=response;
                                    }
                                    //If batter level less than threshold disconnect from master and communicate to master to disconnect from it
                                    if (battery_level < battery_threshold) {
                                        sendReceive.write(dataSerializer.objectToByteArray(device_name + ":Battery Level:Batter level is low"));
                                        //bluetoothAdapter.disable();
                                        //connection_status = new HashMap<BluetoothSocket, ArrayList<String>>();
                                        if(connection_status.size()>0)
                                        {
                                            connection_status.remove(connected_socket.get(0));
                                            connected_socket.remove(0);
                                        }
                                    }

                                }
                            }
                        }
                        //if object received is of type string
                        else if(o instanceof String)
                        {
                            String tempMsg=(String) o;
                            //Toast.makeText(getApplicationContext(),tempMsg,Toast.LENGTH_LONG).show();
                            String[] messages=tempMsg.split(":");
                            //If slave has received Battery level low from master disconnect from master
                            if(messages[2].equals("Batter level is low"))
                            {
                                connStatus.setText("Disconnected");
                                info.setText("Battery low at master");
                                if(connection_status.size()>0)
                                {
                                    serverSocket.close();
                                    connection_status.remove(connected_socket.get(0));
                                    connected_socket.remove(0);
                                }
                            }
                            //else if slave has received message Disconnect from master, disconnect from master
                            else if(messages[2].equals("Disconnect"))
                            {
                                connStatus.setText("Disconnected");
                                info.setText("Disconnected");
                                if(connection_status.size()>0)
                                {
                                    serverSocket.close();
                                    connection_status.remove(connected_socket.get(0));
                                    connected_socket.remove(0);
                                }

                            }
                            //else master is just asking for battery level stats
                            else {
                                battery_check_count++;
                                sendReceive.write(dataSerializer.objectToByteArray(device_name + ":Battery Level:" + Integer.toString(battery_level)));
                            }
                        }
                        else
                        {
                            Toast.makeText(getApplicationContext(),o.getClass().getName(),Toast.LENGTH_LONG).show();
                        }

                    }
                    catch(Exception e)
                    {
                        e.printStackTrace();
                    }
                    break;
                case STATE_BATTERY_LOW:
                    connStatus.setText("Battery Low Can't connect");
                    break;
            }
            return true;
        }
    });
    /*
    This class is mainly responsible for sending and receiving messages based on the socket with the help of input stream and output stream
     */
    private class SendReceive extends Thread
    {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public SendReceive (BluetoothSocket socket)
        {
            bluetoothSocket=socket;
            InputStream tempIn=null;
            OutputStream tempOut=null;


            try
            {
                tempIn=bluetoothSocket.getInputStream();
                tempOut=bluetoothSocket.getOutputStream();
            }
            catch (IOException e)
            {
                if(connection_status.containsKey(bluetoothSocket))
                {
                    if(connection_status.get(bluetoothSocket).get(1)=="free"){
                        available_devices--;
                    }
                    connection_status.remove(bluetoothSocket);
                    connected_socket.remove(bluetoothSocket);

                }
                e.printStackTrace();
            }

            inputStream=tempIn;
            outputStream=tempOut;
        }


        //This will always check if there is any message that has to be sent to a particular socket form this device by always reading the output stream with the help of input stream
        public void run()
        {
            byte[] buffer=new byte[102400];
            int bytes;


            while(battery_level>=battery_threshold)
            {
                try
                {
                    if(connection_status.containsKey(bluetoothSocket)) {
                        bytes = inputStream.read(buffer);
                        System.out.println("I am here: " + bytes);
                        handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                    }

                }
                catch (IOException e)
                {
                    if(connection_status.containsKey(bluetoothSocket))
                    {
                        if(connection_status.get(bluetoothSocket).get(1)=="free"){
                            available_devices--;
                        }
                        connection_status.remove(bluetoothSocket);
                        connected_socket.remove(bluetoothSocket);
                    }
                    e.printStackTrace();
                }
            }
            if(battery_level<battery_threshold)
            {

                try
                {
                    write(dataSerializer.objectToByteArray(device_name+":Battery Level:Batter level is low"));
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
                try
                {
                    bytes=inputStream.read(buffer);
                    handler.obtainMessage(STATE_MESSAGE_RECEIVED,bytes,-1,buffer).sendToTarget();
                }
                catch (IOException e)
                {
                    if(connection_status.containsKey(bluetoothSocket))
                    {
                        if(connection_status.get(bluetoothSocket).get(1)=="free"){
                            available_devices--;
                        }
                        connection_status.remove(bluetoothSocket);
                        connected_socket.remove(bluetoothSocket);
                    }
                    e.printStackTrace();
                    Message message=Message.obtain();
                    message.what=STATE_CONNECTION_FAILED;
                    handler.sendMessage(message);
                }
            }

        }
        //This function is used to write the message to the output stream of the particular device that has to be sent to certain socket
        public void write(byte[] bytes)
        {
            try
            {
                if(battery_level>=battery_threshold)
                {
                    System.out.println("In outputstream: "+bytes);
                    outputStream.write(bytes);
                }
            }
            catch (IOException e)
            {
                e.printStackTrace();
                Message message=Message.obtain();
                message.what=STATE_CONNECTION_FAILED;
                handler.sendMessage(message);
            }
        }

    }
    //--------------------------------------------------------------------------------------------------------
    //for location of device
    @SuppressLint("MissingPermission")
    private void getLastLocation() {
        // check if permissions are given
        if (checkPermissions()) {
            // check if location is enabled
            if (isLocationEnabled()) {
                // getting last location from FusedLocationClient object
                mFusedLocationClient.getLastLocation().addOnCompleteListener(new OnCompleteListener<Location>() {
                    @Override
                    public void onComplete(@NonNull Task<Location> task) {
                        Location location = task.getResult();
                        if (location == null) {
                            requestNewLocationData();
                        } else {
                            myLatitude=Double.toString(location.getLatitude());
                            myLongitude=Double.toString(location.getLongitude());
                            device_loc=myLatitude+","+myLongitude;
                            myLocation.setText("My location: "+ myLatitude + ", " + myLongitude);
                        }
                    }
                });
            } else {
                Toast.makeText(this, "Please turn on" + " your location...", Toast.LENGTH_LONG).show();
                Intent intent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                startActivity(intent);
            }
        } else {
            // if permissions aren't available request for permissions
            requestPermissions();
        }
    }

    @SuppressLint("MissingPermission")
    private void requestNewLocationData() {

        // Initializing LocationRequest
        // object with appropriate methods
        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setInterval(5);
        mLocationRequest.setFastestInterval(0);
        mLocationRequest.setNumUpdates(1);

        // setting LocationRequest
        // on FusedLocationClient
        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mFusedLocationClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.myLooper());
    }

    private LocationCallback mLocationCallback = new LocationCallback() {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            Location mLastLocation = locationResult.getLastLocation();
            myLatitude = Double.toString(mLastLocation.getLatitude());
            myLongitude = Double.toString(mLastLocation.getLongitude());
            myLocation.setText("My location: " + myLatitude + ", " + myLongitude);
        }
    };

    // method to check for permissions
    private boolean checkPermissions() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    // request for location permissions
    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_ID);
    }

    // check if location is enabled
    private boolean isLocationEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
    }

    @Override
    public void
    onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSION_ID) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLastLocation();
            }
        }
    }
}
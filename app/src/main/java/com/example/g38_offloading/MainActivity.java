package com.example.g38_offloading;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
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
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    FusedLocationProviderClient mFusedLocationClient;
    int PERMISSION_UNIQUE_ID = 44;
    int devicesAvailabilityCount =0;

    int REQUEST_ENABLE_BLUETOOTH=1;
    int batteryLevel=0;
    int batteryThreshold=40;

    static final int STATE_LISTENING =1;
    static final int STATE_CONNECTING=2;
    static final int STATE_CONNECTED=3;
    static final int STATE_CONNECTION_FAILED=4;
    static final int STATE_MESSAGE_RECEIVED=5;
    static final int STATE_BATTERY_LOW=6;
    static final int STATE_DISCONNECTED=7;
    static final int STATE_DENIED =8;

    String deviceLocation ="";
    String deviceName;
    String displayMsg="";
    String myLatitude, myLongitude;
    String masterName;

    BluetoothAdapter btAdapter;
    ArrayList<BluetoothSocket> btSocketConnections =new ArrayList<BluetoothSocket>();
    btConnector btConnector;
    public BluetoothServerSocket serverSocket;
    Map<BluetoothSocket,ArrayList<String>> btSocketStatus =new HashMap<BluetoothSocket, ArrayList<String>>();

    Button startOffloading,clearLog;
    TextView myLocation,connStatus,batteryStatus, info;

    // for bluetooth connection
    private static final String APP_NAME= "MobOffloading";
    private static final UUID MY_UUID=UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    DataConversionSerial dataSerializer;
    serialDecoder response_localVar;

    private Handler offloadingHandler=new Handler();

    private BroadcastReceiver batteryMonitor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            batteryLevel=intent.getIntExtra(BatteryManager.EXTRA_LEVEL,0);
            batteryStatus.setText("My battery level: " + String.valueOf(batteryLevel) + "%");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState)  {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayShowHomeEnabled(true);
        actionBar.setIcon(R.mipmap.ic_launcher);

        batteryStatus=(TextView) findViewById(R.id.batteryStatus);
        connStatus = (TextView) findViewById(R.id.connStatus);
        myLocation = (TextView) findViewById(R.id.myLocation);
        info = (TextView) findViewById(R.id.infoView);
        info.setMovementMethod(ScrollingMovementMethod.getInstance());
        startOffloading = (Button) findViewById(R.id.listen);
        clearLog = (Button) findViewById(R.id.clearLog);
        dataSerializer = new DataConversionSerial();

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        getLastLocation();

        this.registerReceiver(this.batteryMonitor,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        deviceName=BluetoothAdapter.getDefaultAdapter().getName();
        btAdapter =BluetoothAdapter.getDefaultAdapter();

        if(!btAdapter.isEnabled())
        {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent,REQUEST_ENABLE_BLUETOOTH);
        }
        startOffloading.setOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v) {
                Intent btIntent=new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                btIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,500);
                startActivity(btIntent);
                slaveListen slavelisten=new slaveListen();
                slavelisten.start();
            }
        });

        clearLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                info.setText("");
            }
        });
    }

    private class slaveListen extends Thread
    {
        public slaveListen()
        {
            try
            {
                if(batteryLevel>=batteryThreshold)
                {
                    serverSocket = btAdapter.listenUsingRfcommWithServiceRecord(APP_NAME, MY_UUID);
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

            if(batteryLevel<batteryThreshold)
            {
                Message message=Message.obtain();
                message.what=STATE_BATTERY_LOW;
                handler.sendMessage(message);
            }
            while(socket==null && batteryLevel>=batteryThreshold)
            {
                try
                {
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
                    btSocketConnections.add(socket);
                    masterName=socket.getRemoteDevice().getName();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                            builder.setMessage( masterName+ " Master wants to offload matrix multiplcation, monitor location and battery level ?").setPositiveButton("Accept", dialogClickListener)
                                    .setNegativeButton("Deny", dialogClickListener).show();
                        }
                    });
                    socket = null;
                }
            }
        }
    }

    private void accept_master(BluetoothSocket socket) {
        Message message = Message.obtain();
        message.what = STATE_CONNECTED;
        handler.sendMessage(message);
        if (!btSocketStatus.containsKey(socket)) {
            ArrayList<String> btSocketTempArray = new ArrayList<String>();
            btSocketTempArray.add(socket.getRemoteDevice().getName());
            btSocketTempArray.add("free");
            devicesAvailabilityCount++;
            btSocketStatus.put(socket, btSocketTempArray);
        }
        btConnector = new btConnector(socket);
        btConnector.start();

        try {
            if (deviceLocation != null && !deviceLocation.isEmpty()) {
                btConnector.write(dataSerializer.objectToByteArray(deviceName + ":Battery Level:" + Integer.toString(batteryLevel) + ":Location:" + deviceLocation));
            } else {
                btConnector.write(dataSerializer.objectToByteArray(deviceName + ":Battery Level:" + Integer.toString(batteryLevel)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
            switch (which){
                case DialogInterface.BUTTON_POSITIVE:
                    accept_master(btSocketConnections.get(0));
                    break;

                case DialogInterface.BUTTON_NEGATIVE:
                    Message message=Message.obtain();
                    message.what=STATE_DENIED;
                    handler.sendMessage(message);
                    send_denied_to_master(btSocketConnections.get(0));
                    btSocketConnections.remove(0);
                    btConnector =null;
                    break;
            }
        }

        private void send_denied_to_master(BluetoothSocket socket) {
            if (!btSocketStatus.containsKey(socket)) {
                ArrayList<String> btSocketTempArray = new ArrayList<String>();
                btSocketTempArray.add(socket.getRemoteDevice().getName());
                btSocketTempArray.add("free");
                devicesAvailabilityCount++;
                btSocketStatus.put(socket, btSocketTempArray);
            }
            btConnector = new btConnector(socket);
            btConnector.start();

            try {
                btConnector.write(dataSerializer.objectToByteArray(deviceName + ":Denied:NoConsent"));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    };
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
                    connStatus.setText("Connected to " +masterName);
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
                case STATE_MESSAGE_RECEIVED:
                    try
                    {
                        byte[] readBuff = (byte[]) msg.obj;

                        Object o=dataSerializer.byteArrayToObject(readBuff);
                        if(o instanceof serialEncoder)
                        {
                            serialEncoder localVarMsg = (serialEncoder) o;
                            displayMsg += masterName +" sent " + Arrays.toString(localVarMsg.getA()) + "\n";
                            info.setText(displayMsg.trim());
                            for (BluetoothSocket socket : btSocketConnections)
                            {
                                if (socket != null)
                                {
                                    int[] result_output = new int[localVarMsg.getB()[0].length];
                                    for (int i = 0; i < localVarMsg.getB()[0].length; i++) {
                                        int sum = 0;
                                        for (int j = 0; j < localVarMsg.getA().length; j++) {
                                            sum += localVarMsg.getA()[j] * localVarMsg.getB()[j][i];

                                        }
                                        result_output[i] = sum;
                                    }
                                    serialDecoder response = new serialDecoder(result_output, localVarMsg.getRow(), deviceName);
                                    if (batteryLevel>=batteryThreshold) {
                                        btConnector.write(dataSerializer.objectToByteArray(response));
                                    }
                                    else
                                    {
                                        response_localVar=response;
                                    }

                                    if (batteryLevel < batteryThreshold) {
                                        btConnector.write(dataSerializer.objectToByteArray(deviceName + ":Battery Level:Battery level low"));
                                        if(btSocketStatus.size()>0)
                                        {
                                            btSocketStatus.remove(btSocketConnections.get(0));
                                            btSocketConnections.remove(0);
                                        }
                                    }

                                }
                            }
                        }
                        else if(o instanceof String)
                        {
                            String localVarMsg=(String) o;
                            String[] messages=localVarMsg.split(":");
                            if(messages[2].equals("Battery level low"))
                            {
                                info.setText("Master's Battery is Low");
                                connStatus.setText("Disconnected");
                                if(btSocketStatus.size()>0)
                                {
                                    serverSocket.close();
                                    btSocketStatus.remove(btSocketConnections.get(0));
                                    btSocketConnections.remove(0);
                                }
                            }
                            else if(messages[2].equals("Disconnect"))
                            {
                                connStatus.setText("Disconnected");
                                info.setText("Disconnected");
                                if(btSocketStatus.size()>0)
                                {
                                    serverSocket.close();
                                    btSocketStatus.remove(btSocketConnections.get(0));
                                    btSocketConnections.remove(0);
                                }

                            }
                            else {
                                btConnector.write(dataSerializer.objectToByteArray(deviceName + ":Battery Level:" + Integer.toString(batteryLevel)));
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
    private class btConnector extends Thread
    {
        private final BluetoothSocket bluetoothSocket;
        private final InputStream inputStream;
        private final OutputStream outputStream;

        public btConnector(BluetoothSocket socket)
        {
            bluetoothSocket=socket;
            InputStream localVarIn=null;
            OutputStream localVarOut=null;


            try
            {
                localVarIn=bluetoothSocket.getInputStream();
                localVarOut=bluetoothSocket.getOutputStream();
            }
            catch (IOException e)
            {
                if(btSocketStatus.containsKey(bluetoothSocket))
                {
                    if(btSocketStatus.get(bluetoothSocket).get(1)=="free"){
                        devicesAvailabilityCount--;
                    }
                    btSocketStatus.remove(bluetoothSocket);
                    btSocketConnections.remove(bluetoothSocket);

                }
                e.printStackTrace();
            }

            inputStream=localVarIn;
            outputStream=localVarOut;
        }

        public void run()
        {
            byte[] buffer=new byte[102400];
            int bytes;
            while(batteryLevel>=batteryThreshold)
            {
                try
                {
                    if(btSocketStatus.containsKey(bluetoothSocket)) {
                        bytes = inputStream.read(buffer);
                        handler.obtainMessage(STATE_MESSAGE_RECEIVED, bytes, -1, buffer).sendToTarget();
                    }

                }
                catch (IOException e)
                {
                    if(btSocketStatus.containsKey(bluetoothSocket))
                    {
                        if(btSocketStatus.get(bluetoothSocket).get(1)=="free"){
                            devicesAvailabilityCount--;
                        }
                        btSocketStatus.remove(bluetoothSocket);
                        btSocketConnections.remove(bluetoothSocket);
                    }
                    e.printStackTrace();
                }
            }
            if(batteryLevel<batteryThreshold)
            {

                try
                {
                    write(dataSerializer.objectToByteArray(deviceName+":Battery Level:Battery level low"));
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
                    if(btSocketStatus.containsKey(bluetoothSocket))
                    {
                        if(btSocketStatus.get(bluetoothSocket).get(1)=="free"){
                            devicesAvailabilityCount--;
                        }
                        btSocketStatus.remove(bluetoothSocket);
                        btSocketConnections.remove(bluetoothSocket);
                    }
                    e.printStackTrace();
                    Message message=Message.obtain();
                    message.what=STATE_CONNECTION_FAILED;
                    handler.sendMessage(message);
                }
            }

        }
        public void write(byte[] bytes)
        {
            try
            {
                if(batteryLevel>=batteryThreshold)
                {
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
                            deviceLocation=myLatitude+","+myLongitude;
//
                            myLocation.setText("My location: " + myLatitude + ", " + myLongitude);
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

        LocationRequest mLocationRequest = new LocationRequest();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationRequest.setInterval(5);
        mLocationRequest.setFastestInterval(0);
        mLocationRequest.setNumUpdates(1);

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
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION}, PERMISSION_UNIQUE_ID);
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

        if (requestCode == PERMISSION_UNIQUE_ID) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getLastLocation();
            }
        }
    }
}
package a13_12solutions.touchless;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.Buffer;
import java.util.Set;
import java.util.UUID;

import weka.classifiers.trees.J48;
import weka.core.Instances;
import weka.core.converters.ConverterUtils.DataSource;


/**TODO: Add Weka lib
 * setup the Weka obj with train and test data
 * when getting wearable inputdata classify it trgough weka -> produce label
 *      send label to cloudmqqt
 *
 */

public class MainActivity extends AppCompatActivity {

    private static final String TAG="BT_connect";
    private static final boolean DEBUG = true;
    private static final String SENSITIVITY ="s100000";
    private static final String[] LABELS = {"up","left","down","right","tilt_left","tilt_right"};

    private BluetoothAdapter mBluetoothAdapter;
    private Set<BluetoothDevice> pairedDevices;
    private BluetoothDevice mDevice;
    private ConnectThread mConnectThread;

    private ConnectedThread mConnectedThread;


    //Stores all sensor-data
    private LiveGestureData dataStore = new LiveGestureData();
    private J48 tree;

    //Can probably remove Handler
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            byte[] writeBuf = (byte[]) msg.obj;
            int begin = (int)msg.arg1;
            int end = (int)msg.arg2;

            switch(msg.what) {
                case 1:
                    String writeMessage = new String(writeBuf);
                    writeMessage = writeMessage.substring(begin, end);
                    Log.d(TAG,"handleMessage:"+writeMessage);
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            setUpWeka();
            initBlueToothConnection();
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void setUpWeka() throws Exception{
        AssetManager assetManager = getAssets();
        InputStream fr = assetManager.open("testdata.arff");
        DataSource source = new DataSource(fr);
        Instances data = source.getDataSet();
        // setting class attribute if the data format does not provide this information
        // For example, the XRFF format saves the class attribute information as well
        if (data.classIndex() == -1)
            data.setClassIndex(data.numAttributes() - 1);
        // setting class attribute
        String[] options = new String[1];
        options[0] = "-U";            // unpruned tree
        tree = new J48();         // new instance of tree
        tree.setOptions(options);     // set the options
        tree.buildClassifier(data);   // build classifier
        Log.d(TAG, "WEKA TREE BUILT!");
    }

    private void initBlueToothConnection() {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if(mBluetoothAdapter != null){

            //prompt user to enable bt, unpair other devices in android os - otherwise need to handle identification
            if(!mBluetoothAdapter.isEnabled()){
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent,1);
            }else{
                startConnection();
            }
        }
    }


  /*  private void classifyInputData()throws Exception{
        // load test data TODO remove however this is how the live recognition should be handled? check gesture_rec in processing to compare!
        BufferedReader breader = new BufferedReader (new FileReader("/Users/ag6031/Dropbox/IOTAP/Teaching/IoTaP Course/Labs/Lab 3/test1.arff"));
        Instances test = new Instances (breader);
        test.setClassIndex(test.numAttributes() -1);
        //label the test data
        int classIndex = train.numAttributes() -1;
        Instances labeled = new Instances(test);
        for (int i = 0; i < test.numInstances(); i++){
            double clsLabel = tree.classifyInstance(test.instance(i)) ;
            labeled.instance(i).setClassValue(clsLabel);
            System.out.println(labeled.instance(i).attribute(classIndex).value((int) clsLabel));
        }
        //  save labeled data
        BufferedWriter writer = new BufferedWriter (
                new FileWriter("/Users/ag6031/Dropbox/IOTAP/Teaching/IoTaP Course/Labs/Lab 3/labeled.arff"));
        writer.write(labeled.toString());
        writer.close();
    }*/

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        //if user enabled bt
        if(resultCode == RESULT_OK){
            startConnection();
        }

    }

    /**Starts the connection with paired bluetooth device
     *
     */
    private void startConnection() {
        Log.d(TAG,"in startConnection");
        pairedDevices = mBluetoothAdapter.getBondedDevices();
        if(pairedDevices.size()>0){
            Log.d(TAG, "paired device found");

            for(BluetoothDevice device : pairedDevices){
                mDevice = device;
                Log.d(TAG, "got paired device");

            }
        }

        mConnectThread = new ConnectThread(mDevice);
        mConnectThread.start();
    }



    private class ConnectThread extends Thread {
        private BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;
            mmDevice = device;
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
                Log.d(TAG, "Connection thread initiated");

            } catch (IOException e) {
                Log.d(TAG, e.getMessage());

            }
            mmSocket = tmp;
        }

        public void run() {
            mBluetoothAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
                Log.d(TAG, "connected to bt-device");
                mConnectedThread = new ConnectedThread(mmSocket);
                mConnectedThread.start();


            } catch (IOException connectException) {
                Log.d(TAG, "ERROR in connect: "+connectException.getMessage());

                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.d(TAG, "ERROR in close"+closeException.getMessage());

                }
                return;
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream; //refactor

        public ConnectedThread(BluetoothSocket socket) {
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
                Log.d(TAG, "Connectedthread initiated");

            } catch (IOException e) {
                Log.d(TAG, "stream: "+e.getMessage());
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            try {
                //Adjust sensitivity of gyro.
                mmOutStream.write(SENSITIVITY.getBytes());
                mmOutStream.flush();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            byte[] buffer = new byte[1024];
            int begin = 0;
            int bytes = 0;
            while (true) {
                try {
                    bytes += mmInStream.read(buffer, bytes, buffer.length - bytes);
                    for (int i = begin; i < bytes; i++) {
                        String inputData = new String(buffer);
                        Log.d("BT_BUFFER", "recieved  inputData, bynteNbr: "+i);
                        handleInputData(inputData);
                       /* if (buffer[i] == "#".getBytes()[0]) {
                            Log.d("BT_BUFFER", "######");

                            mHandler.obtainMessage(1, begin, i, buffer).sendToTarget();
                            begin = i + 1;
                            if (i == bytes - 1) {
                                bytes = 0;
                                begin = 0;
                            }
                        }*/
                    }
                } catch (IOException e) {
                    Log.d(TAG,"readbuffer: "+e.getMessage());
                    break;
                }
            }
        }

        private void handleInputData(String inputData) {
            Log.d("BT_handleInputData", "Parsing input into arr\n\n");

            if(inputData!= null && inputData.length()>0){
                //Split rows of inputData
                String[] inputDataRows = inputData.split("\n");

                int length;
                if(inputDataRows.length>30){
                    length=30;
                }else{
                    length = inputDataRows.length;
                }
                Log.d("BT_handleInputData","nbr of rows: "+length);
                //For each row, put values into data-store TODO: limit to 30 rows of data!
                for(int i = 0; i<length; i++){
                    String[] row = inputDataRows[i].split(",");
                    if(row.length>=7 && row[0].equals("h")){
                        dataStore.putVals(row[1],row[2],row[3],row[4],row[5],row[6]);
                    }

                }


            }
        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
            }
        }
    }


}


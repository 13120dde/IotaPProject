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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import weka.classifiers.trees.J48;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.FastVector;
import weka.core.Instance;
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
    private static final String SENSITIVITY ="s24000";
    private static final String[] LABELS = {"up","left","down","right","tilt_left","tilt_right"};

    private BluetoothAdapter mBluetoothAdapter;
    private Set<BluetoothDevice> pairedDevices;
    private BluetoothDevice mDevice;
    private ConnectThread mConnectThread;

    private ConnectedThread mConnectedThread;


    //Weka related variables
    private LiveGestureData dataStore = new LiveGestureData();
    private J48 tree;
    private Instances train, test;

    //Can probably remove Handler
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            Log.d(TAG,"handleMessage:"+"OKFSEOKKO");

         /*   byte[] writeBuf = (byte[]) msg.obj;
            int begin = (int)msg.arg1;
            int end = (int)msg.arg2;*/

            switch(msg.what) {
                case 1:
                    String writeMessage = (String )msg.obj;
                    //writeMessage = writeMessage.substring(begin, end);
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
        train = source.getDataSet();
        // setting class attribute if the data format does not provide this information
        // For example, the XRFF format saves the class attribute information as well
        if (train.classIndex() == -1)
            train.setClassIndex(train.numAttributes() - 1);
        // setting class attribute
        String[] options = new String[1];
        options[0] = "-U";            // unpruned tree
        tree = new J48();         // new instance of tree
        tree.setOptions(options);     // set the options
        tree.buildClassifier(train);   // build classifier
        if(DEBUG){
            Log.d(TAG, "WEKA TREE BUILT!");
            Log.d(TAG, tree.toString());

        }
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
        if(DEBUG)
            Log.d(TAG,"in startConnection");
        
        pairedDevices = mBluetoothAdapter.getBondedDevices();
        if(pairedDevices.size()>0){
            if(DEBUG)
                Log.d(TAG, "paired device found");

            for(BluetoothDevice device : pairedDevices){
                mDevice = device;
                if(DEBUG)
                    Log.d(TAG, "got paired device: "+mDevice.toString());

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
                if(DEBUG)
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
                if(DEBUG)
                    Log.d(TAG, "connected to bt-device");
                
                mConnectedThread = new ConnectedThread(mmSocket);
                mConnectedThread.start();


            } catch (IOException connectException) {
                if(DEBUG)
                    Log.d(TAG, "ERROR in connect: "+connectException.getMessage());

                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    if(DEBUG)
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
                if(DEBUG)
                    Log.d(TAG, "Connectedthread initiated");

            } catch (IOException e) {
                Log.d(TAG, "stream: "+e.getMessage());
            }
            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            try {
                //Adjust sensitivity of gyro.
                //mmOutStream.write(SENSITIVITY.getBytes());
               // mmOutStream.write("w30".getBytes());
              //  mmOutStream.write("f20".getBytes());
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
                        /*if(inputData!= null && inputData.length()>0){
                            double[] vals = handleInputData(inputData);

                            classifyTuple(vals);

                        }*/
                        if (buffer[i] == "#".getBytes()[0]) {
                            Log.d("BT_BUFFER", "######");

                            mHandler.obtainMessage(1, begin, i, buffer).sendToTarget();
                            begin = i + 1;
                            if (i == bytes - 1) {
                                bytes = 0;
                                begin = 0;
                            }
                        }
                    }


                } catch (IOException e) {
                    Log.d(TAG,"readbuffer: "+e.getMessage());
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
                }
            }
        }

        private double[] handleInputData(String inputData) {
            Log.d("BT_handleInputData", "Parsing input into arr\n\n");
            double[] vals = new double[120];

            if(inputData!= null && inputData.length()>0){
                //Split rows of inputData
                String[] inputDataRows = inputData.split("\n");

                //Limit to 30 tuples
                int length;
                if(inputDataRows.length>20){
                    length=20;
                }else{
                    length = inputDataRows.length;
                }
                int valsIndex = 0;
                Log.d("BT_handleInputData","inputData: "+inputData);
                for(int i = 0; i<length; i++){
                    String[] row = inputDataRows[i].split(",");
                    if(row.length>=7 && row[0].equals("h")){

                        for(int j = 1; j<row.length;j++){
                            if(row[j]!=null){
                                vals[valsIndex] = Double.parseDouble(row[j]);
                                valsIndex++;
                            }
                        }

                    }
                }



            }
            return vals;
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

    //Throws exception b/c Instance is not compatible with testdata.arff
    private void classifyTuple(double[] blueToothData) throws Exception {
        //Parse values to match the .arff file's attributes
       /* double gxAcc = Double.parseDouble(gxAccel);
        double gyAcc = Double.parseDouble(gyAccel);
        double gzAcc = Double.parseDouble(gzAccel);
        double gvRe = Double.parseDouble(gvRef);
        double gxRat = Double.parseDouble(gxRate);
        double gyRat = Double.parseDouble(gyRate);*/

        //Declaring attributes
        /*
        Attribute AccX = new Attribute("AccX");
        Attribute AccY = new Attribute("AccY");
        Attribute AccZ = new Attribute("AccZ");
        Attribute GyrX = new Attribute("GyrX");
        Attribute GyrY = new Attribute("GyrY");
        Attribute GyrZ = new Attribute("GyrZ");*/
        List<Attribute> attributes = new ArrayList<>();

        for(int i = 1; i <=20; i++){
            attributes.add(new Attribute("AccX"+i));
            attributes.add(new Attribute("AccY"+i));
            attributes.add(new Attribute("AccZ"+i));
            attributes.add(new Attribute("GyrX"+i));
            attributes.add(new Attribute("GyrY"+i));
            attributes.add(new Attribute("GyrZ"+i));
        }

        //Declaring the class attribute and add labels
        FastVector fvClassVal = new FastVector(6);
        fvClassVal.addElement("up");
        fvClassVal.addElement("left");
        fvClassVal.addElement("down");
        fvClassVal.addElement("right");
        fvClassVal.addElement("tilt_left");
        fvClassVal.addElement("tilt_right");
        Attribute classLables = new Attribute("Label", fvClassVal);

        //Declaring feature vector and add attributes
        FastVector fvWekaAttributes = new FastVector(attributes.size()+1);
      /*  fvWekaAttributes.addElement(AccX);
        fvWekaAttributes.addElement(AccY);
        fvWekaAttributes.addElement(AccZ);
        fvWekaAttributes.addElement(GyrX);
        fvWekaAttributes.addElement(GyrY);
        fvWekaAttributes.addElement(GyrZ);
        fvWekaAttributes.addElement(classLables);*/
        for(int i = 0; i < attributes.size(); i++){
            fvWekaAttributes.addElement(attributes.get(i));
        }
        fvWekaAttributes.addElement(classLables);


        Instances dataset = new Instances("BTTuple", fvWekaAttributes,0);


        Instance tuple = new DenseInstance(1.0,blueToothData);
        dataset.add(tuple);
        dataset.setClassIndex(dataset.numAttributes()-1);
        int category = (int) tree.classifyInstance(dataset.instance(0));
        System.out.println(LABELS[category]);

    }


}


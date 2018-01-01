package a13_12solutions.touchless;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

/**Android app that reads gyroscope data received by a wearable sensor and classifies the data based
 * on Weka's J48 classifier.
 * The app builds a decision tree from a preprocessed data set which is used to classify the sensor
 * output. Once classified, the app communicates the result with cloud MQTT.
 *
 * Created by: Patrik Lind, 2018-01-01
 *
 */
public class MainActivity extends AppCompatActivity {


    private final boolean DEBUG = true;
    private final String TAG="BT_MainActivity",
        SENSITIVITY ="s44000", WINDOW = "w30", FREQUENCY ="f30";
    private final String[] LABELS = {"up","left","down","right","tilt_left","tilt_right"};

    //Bluetooth variables
    private BluetoothAdapter mBluetoothAdapter;
    private Set<BluetoothDevice> pairedDevices;
    private BluetoothDevice mDevice;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;

    //Variables for storing sensor-data before parsing/preprocessing/classifying.
    private final int NBR_OF_VALS = 120;
    private List<Double> sensorVals = new ArrayList<Double>();

    private J48 tree;
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
                    if(DEBUG)
                        Log.d("BT_handleMsg", writeMessage);
                    try {

                        handleInputData(writeMessage);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    break;
            }
        }
    };
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        try {
            buildClassifier();
            initBlueToothConnection();
        } catch (Exception e) {
            e.printStackTrace();
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
    
    /**Builds a decision tree based on the training set (.arff file) in assets folder. The tree is 
     * a unpruned decision tree where C4.5 is used as attribute selection method.
     *
     * @throws Exception
     */
    private void buildClassifier() throws Exception{
        AssetManager assetManager = getAssets();
        InputStream fr = assetManager.open("testdata.arff");
        DataSource source = new DataSource(fr);
        Instances train = source.getDataSet();
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
        if(DEBUG)
            Log.d(TAG, tree.toString());

    }

    /**Checks if the device has bluetooth and if it's paired with any BT devices.
     * 
     */
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


    /**Starts the connection with paired bluetooth device and starts a new thread that handles the 
     * connection.
     * 
     * CAUTION: This method works only when there is exactly 1 paired device. To prevent errors, 
     * unpair all other BT devices on the phone.
     *
     */
    private void startConnection() {
        if(DEBUG)
            Log.d(TAG,"in startConnection");

        pairedDevices = mBluetoothAdapter.getBondedDevices();
        if(pairedDevices.size()>0){

            for(BluetoothDevice device : pairedDevices){
                mDevice = device;
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
                connectException.printStackTrace();
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    closeException.printStackTrace();

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

    /**This class is responsible for reading/writing sensor data from the connected BT device.
     * 
     */
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
                mmOutStream.write(SENSITIVITY.getBytes());
                mmOutStream.write(WINDOW.getBytes());
                mmOutStream.write(FREQUENCY.getBytes());
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
                        if(DEBUG)
                            Log.d("BT_BUFFER", "received  inputData, bynteNbr: "+i);
                        
                        if (buffer[i] == "h".getBytes()[0]) {
                            mHandler.obtainMessage(1, begin, i, buffer).sendToTarget();
                            begin = i + 1;
                            if (i == bytes - 1) {
                                bytes = 0;
                                begin = 0;
                            }
                        }
                    }


                } catch (IOException e) {
                    e.printStackTrace();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                    break;
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

    /**Parses the string into double and adds the values to the arrayList. One the arrayList has 
     * enough of values (NBR_OF_VALS) the values in arrayList will be sent to classifyTuple(...) and
     * the list will be cleared in preparation for storing the next set of data.
     * 
     * @param inputData : String
     * @throws Exception
     */
    private void handleInputData(String inputData) throws Exception {

        Log.d("BT_handleInputData", "input: "+inputData+" - nbr:"+sensorVals.size());

        String []input = inputData.substring(1, inputData.length()-1).split(",");
        for(int i = 0 ; i<input.length; i++){
            sensorVals.add(Double.parseDouble(input[i]));
        }

        if(sensorVals.size()>=NBR_OF_VALS){
            double[] vals = new double[NBR_OF_VALS];
            for(int i =0; i< NBR_OF_VALS; i++){
                vals[i]=sensorVals.get(i);
                Log.d("BT_handleInputdata", ""+vals[i]);

            }
            sensorVals.clear();
            //TODO: preprocess the data before classifying
            classifyTuple(vals);
        }

    }

    /**Creates a data set from the provided argument and classifies the dataset. The resulting 
     * classifier will in turn be sent to the cloudMQTT. 
     * 
     * @param blueToothData : double[]
     * @throws Exception
     */
    private void classifyTuple(double[] blueToothData) throws Exception {

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


        for (int i = 0; i<LABELS.length; i++){
            fvClassVal.addElement(LABELS[i]);
        }

        Attribute classLables = new Attribute("Label", fvClassVal);

        //Declaring feature vector and add attributes
        FastVector fvWekaAttributes = new FastVector(attributes.size()+1);

        for(int i = 0; i < attributes.size(); i++){
            fvWekaAttributes.addElement(attributes.get(i));
        }
        fvWekaAttributes.addElement(classLables);


        Instances dataset = new Instances("BTTuple", fvWekaAttributes,0);


        Instance tuple = new DenseInstance(1.0,blueToothData);
        dataset.add(tuple);
        dataset.setClassIndex(dataset.numAttributes()-1);
        int category = (int) tree.classifyInstance(dataset.instance(0));
        Log.d("BT_classifyTuple",LABELS[category]);
        sendToMQTT(LABELS[category]);

    }

    private void sendToMQTT(String s) {
    }


}


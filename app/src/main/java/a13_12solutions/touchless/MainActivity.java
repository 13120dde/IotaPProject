package a13_12solutions.touchless;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import org.eclipse.paho.android.service.MqttAndroidClient;

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
 * input. Once classified, the app communicates the result with cloudMQTT.com.
 *
 * Created by: Patrik Lind, 2018-01-01
 *
 */
public class MainActivity extends AppCompatActivity {


    private final boolean DEBUG = true;
    private final String TAG="BT_MainActivity",
        SENSITIVITY ="s24000", WINDOW = "w20", FREQUENCY ="f60";
    private final String[] LABELS = {"up","left","down","right","tilt_left","tilt_right"};

    //Bluetooth variables
    private BluetoothAdapter mBluetoothAdapter;
    private Set<BluetoothDevice> pairedDevices;
    private BluetoothDevice mDevice;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;

    //MQTT variables
    private MqttAndroidClient client;
    private PahoMqttClient pahoMqttClient;

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

        pahoMqttClient = new PahoMqttClient();
        client = pahoMqttClient.getMqttClient(getApplicationContext(), Constants.MQTT_BROKER_URL, Constants.CLIENT_ID);

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
        InputStream is = assetManager.open("train_data3.arff");
        DataSource source = new DataSource(is);
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
            //Preprocessing BT-data actually lowers the accuracy
            slidingWindow(vals);
            minMax(vals);
            classifyTuple(vals);
        }

    }

    private void slidingWindow(double[] vals) {

        /* 1. Smooth out data with 'Moving average' where sliding window = 5 */
        double[] slidingWindow = new double[5];
        for(int i =0; i<vals.length;i++){

            slidingWindow[0] = vals[i];
            if(i<6){
                slidingWindow[1] = vals[NBR_OF_VALS+i-12];
                slidingWindow[2] = vals[NBR_OF_VALS+i-6];
                slidingWindow[3] = vals[i+6];
                slidingWindow[4] = vals[i+12];
            }
            else if(i<12 && i>=6){
                slidingWindow[1] = vals[NBR_OF_VALS+i-12];
                slidingWindow[2] = vals[i-6];
                slidingWindow[3] = vals[i+6];
                slidingWindow[4] = vals[i+12];
            }
            else if(i>=12 && i<NBR_OF_VALS-12){
                slidingWindow[1] = vals[i-12];
                slidingWindow[2] = vals[i-6];
                slidingWindow[3] = vals[i+6];
                slidingWindow[4] = vals[i+12];

            }
            else if(i>=12 && i<NBR_OF_VALS-6){
                slidingWindow[1] = vals[i-12];
                slidingWindow[2] = vals[i-6];
                slidingWindow[3] = vals[i+6];
                slidingWindow[4] = vals[((i+12)-NBR_OF_VALS)];

            }
            else{
                slidingWindow[1] = vals[i-12];
                slidingWindow[2] = vals[i-6];
                slidingWindow[3] = vals[((i+6)-NBR_OF_VALS)];
                slidingWindow[4] = vals[((i+12)-NBR_OF_VALS)];

            }
            double sum=0;
            for(int j = 0;j<slidingWindow.length;j++){
                sum+=slidingWindow[j];
            }
            vals[i] = sum / slidingWindow.length;

        }
    }

    private void minMax(double[] vals){

        double newMax = 500, newMin=-500;

        double[] oldMin = {Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE},
                oldMax = {Double.MIN_VALUE,Double.MIN_VALUE,Double.MIN_VALUE,Double.MIN_VALUE,Double.MIN_VALUE,Double.MIN_VALUE};

        //1. Find old min/max for each category
        for(int i= 0; i<=NBR_OF_VALS-6;i+=6){

            //Check AccX
            if(vals[i]<oldMin[0]){
                oldMin[0]=vals[i];
            }
            if(vals[i]>oldMax[0]){
                oldMax[0] = vals[i];
            }

            //Check AccY
            if(vals[i+1]<oldMin[1]){
                oldMin[1]=vals[i+1];
            }
            if(vals[i+1]>oldMax[0]){
                oldMax[1] = vals[i+1];
            }

            //Check AccZ
            if(vals[i+2]<oldMin[2]){
                oldMin[2]=vals[i+2];
            }
            if(vals[i+2]>oldMax[2]){
                oldMax[2] = vals[i+2];
            }

            //Check GyrX
            if(vals[i+3]<oldMin[3]){
                oldMin[3]=vals[i+3];
            }
            if(vals[i+3]>oldMax[3]){
                oldMax[3] = vals[i+3];
            }

            //Check GyrY
            if(vals[i+4]<oldMin[4]){
                oldMin[4]=vals[i+4];
            }
            if(vals[i+4]>oldMax[4]){
                oldMax[4] = vals[i+4];
            }

            //Check GyrZ
            if(vals[i+5]<oldMin[5]){
                oldMin[5]=vals[i+5];
            }
            if(vals[i+5]>oldMax[5]){
                oldMax[5] = vals[i+5];
            }
        }

        int indexToOldMinMaxVals = 0;

        //2. Normalize each data point in arr.
        for(int i =0; i<vals.length;i++){


            if(indexToOldMinMaxVals % 6 ==0){
                indexToOldMinMaxVals =0;
            }

            Log.d("BT_MinMax", "old val: "+vals[i]+"\noldMin: "+oldMin[indexToOldMinMaxVals]+"\noldMax: "+oldMax[indexToOldMinMaxVals]);
            vals[i] = ((vals[i]-oldMin[indexToOldMinMaxVals ])
                    /(oldMax[indexToOldMinMaxVals]-oldMin[indexToOldMinMaxVals]))*(newMax-newMin)+newMin;

            indexToOldMinMaxVals++;
            Log.d("BT_MinMax", "new val: "+vals[i]);

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
        pahoMqttClient.publishMessage(client, LABELS[category], 1, Constants.PUBLISH_TOPIC);

    }



}


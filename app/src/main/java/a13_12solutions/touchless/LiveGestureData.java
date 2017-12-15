package a13_12solutions.touchless;

import android.util.Log;

import java.util.ArrayList;

/**
 * Created by Patrik Lind on 2017-12-15.
 */

public class LiveGestureData {
    private ArrayList<String> gxAccel = new ArrayList<>();
    private ArrayList<String> gyAccel = new ArrayList<>();
    private ArrayList<String> gzAccel = new ArrayList<>();
    private ArrayList<String> gvRef = new ArrayList<>();
    private ArrayList<String> gxRate= new ArrayList<>();
    private ArrayList<String> gyRate = new ArrayList<>();

    private static final String TAG="DATA_STORE";

    public void putVals(String gxAccel, String gyAccel, String gzAccel, String gvRef, String gxRate, String gyRate){
        this.gxAccel.add(gxAccel);
        this.gyAccel.add(gyAccel);
        this.gzAccel.add(gzAccel);
        this.gvRef.add(gvRef);
        this.gxRate.add(gxRate);
        this.gyRate.add(gyRate);
        Log.d(TAG,"Added vals:" +gxAccel+", "+gyAccel+", "+gzAccel+", "+gvRef+", "+gxRate+", "+gyRate);
    }
}

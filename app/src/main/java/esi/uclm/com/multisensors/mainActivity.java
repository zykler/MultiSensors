package esi.uclm.com.multisensors;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import java.text.DecimalFormat;

import static android.util.FloatMath.*;

public class mainActivity extends Activity implements SensorEventListener, FusedGyroscopeSensorListener{

    public static final float EPSILON = 0.000000001f;

    private static final String tag = mainActivity.class.getSimpleName();
    private static final float NS2S = 1.0f / 1000000000.0f;
    private static final int MEAN_FILTER_WINDOW = 10;
    private static final int MIN_SAMPLE_COUNT = 30;

    private boolean hasInitialOrientation = false;
    private boolean stateInitializedCalibrated = false;

    private boolean useFusedEstimation = false;

    private DecimalFormat df;

    // Calibrated maths.
    private float[] currentRotationMatrix;
    private float[] deltaRotationMatrix;
    private float[] deltaRotationVector;
    private float[] gyroscopeOrientation;

    // accelerometer and magnetometer based rotation matrix
    private float[] initialRotationMatrix;

    // accelerometer vector
    private float[] acceleration;

    // magnetic field vector
    private float[] magnetic;

    private FusedGyroscopeSensor fusedGyroscopeSensor;

    private int accelerationSampleCount = 0;
    private int magneticSampleCount = 0;

    private long timestampOld = 0;

    private MeanFilter accelerationFilter;
    private MeanFilter magneticFilter;

    /* We need the SensorManager to register for Sensor Events */
    private SensorManager mSensorManager;

    private TextView calibrationX;
    private TextView calibrationY;
    private TextView calibrationZ;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initUI();
        initMaths();
        initSensors();
        initFilters();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        /**
        * Handle action bar item clicks here. The action bar will
        * automatically handle clicks on the Home/Up button, so long
        * as you specify a parent activity in AndroidManifest.xml.
        **/
        switch (item.getItemId()){
            case R.id.action_reset:
                unregisterListeners();
                registerListeners();

                return true;

            case R.id.action_config:
                Intent intent = new Intent();
                intent.setClass(this, ConfigActivity.class);
                startActivity(intent);

                return true;

            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        //TODO
    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            onAccelerationSensorChanged(event.values, event.timestamp);
        }

        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            onMagneticSensorChanged(event.values, event.timestamp);
        }

        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            onGyroscopeSensorChanged(event.values, event.timestamp);
        }
    }

    @Override
    public void onAngularVelocitySensorChanged(float[] angularVelocity, long timeStamp) {
        calibrationX.setText(df.format(Math.toDegrees(angularVelocity[0])));
        calibrationY.setText(df.format(Math.toDegrees(angularVelocity[1])));
        calibrationZ.setText(df.format(Math.toDegrees(angularVelocity[2])));
    }

    public void onAccelerationSensorChanged(float[] acceleration, long timeStamp) {
        /* Copy of the values of the sensor inputs */
        System.arraycopy(acceleration, 0, this.acceleration, 0,
                acceleration.length);

        /* Apply mean Filter to smooth the sensor inputs */
        this.acceleration = accelerationFilter.filterFloat(this.acceleration);

        /* Count the number of counts */
        accelerationSampleCount++;

        /**
        * Only determine the initial orientation after the acceleration sensor
        * and magnetic sensor have had enough time to be smoothed by the mean
        * filters. Also, only do this if the orientation hasn't already been
        * determined since we only need it once.
        **/
        if (accelerationSampleCount > MIN_SAMPLE_COUNT
                && magneticSampleCount > MIN_SAMPLE_COUNT
                && !hasInitialOrientation)
        {
            calculateOrientation();
        }
    }

    public void onMagneticSensorChanged(float[] magnetic, long timeStamp) {
        /* Copy of the values of the sensor inputs */
        System.arraycopy(magnetic, 0, this.magnetic, 0, magnetic.length);

        /* Apply mean Filter to smooth the sensor inputs */
        this.magnetic = magneticFilter.filterFloat(this.magnetic);

        /* Count the number of counts */
        magneticSampleCount++;
    }

    /* Calculate orientation over time*/
    public void onGyroscopeSensorChanged(float[] gyroscope, long timestamp) {

        /* Don't start until first orientation has been calculated */
        if (!hasInitialOrientation)
        {
            return;
        }

        /* Initialization of the gyroscope based rotation matrix */
        if (!stateInitializedCalibrated) {
            currentRotationMatrix = matrixMultiplication(
                    currentRotationMatrix, initialRotationMatrix);

            stateInitializedCalibrated = true;
        }
        // This timestep's delta rotation to be multiplied by the current rotation
        // after computing it from the gyro sample data.
        if (timestampOld != 0 && stateInitializedCalibrated) {

            final float dT = (timestamp - timestampOld) * NS2S;

            // Axis of the rotation sample, not normalized yet.
            float axisX = gyroscope[0];
            float axisY = gyroscope[1];
            float axisZ = gyroscope[2];

            /* Angular speed */
            float omegaMagnitude = sqrt(axisX*axisX + axisY*axisY + axisZ*axisZ);


            /**
            * Normalize rotator vector (in case it is big enough to get the axis
            *
            * EPSILON represents maximum allowable margin of error
            *
            * */
            if (omegaMagnitude > EPSILON) {
                axisX /= omegaMagnitude;
                axisY /= omegaMagnitude;
                axisZ /= omegaMagnitude;
            }

            // Integrate around this axis with the angular speed by the timestep
            // in order to get a delta rotation from this sample over the timestep
            // We will convert this axis-angle representation of the delta rotation
            // into a quaternion before turning it into the rotation matrix.
            float thetaOverTwo = omegaMagnitude * dT / 2.0f;

            float sinThetaOverTwo = sin(thetaOverTwo);
            float cosThetaOverTwo = cos(thetaOverTwo);

            deltaRotationVector[0] = sinThetaOverTwo * axisX;
            deltaRotationVector[1] = sinThetaOverTwo * axisY;
            deltaRotationVector[2] = sinThetaOverTwo * axisZ;
            deltaRotationVector[3] = cosThetaOverTwo;

            SensorManager.getRotationMatrixFromVector(
                    deltaRotationMatrix,
                    deltaRotationVector);

            currentRotationMatrix = matrixMultiplication(
                    currentRotationMatrix,
                    deltaRotationMatrix);

            SensorManager.getOrientation(currentRotationMatrix,
                    gyroscopeOrientation);
        }
        timestampOld = timestamp;

        // User code should concatenate the delta rotation we computed with the current rotation
        // in order to get the updated rotation.
        // rotationCurrent = rotationCurrent * deltaRotationMatrix;

        calibrationX.setText(df.format(Math.toDegrees(gyroscopeOrientation[0])));
        calibrationY.setText(df.format(Math.toDegrees(gyroscopeOrientation[1])));
        calibrationZ.setText(df.format(Math.toDegrees(gyroscopeOrientation[2])));
    }

    /* Mathematical formula for calculating the rotation */
    private float[] matrixMultiplication(float[] a, float[] b) {
        float[] result = new float[9];

        result[0] = a[0] * b[0] + a[1] * b[3] + a[2] * b[6];
        result[1] = a[0] * b[1] + a[1] * b[4] + a[2] * b[7];
        result[2] = a[0] * b[2] + a[1] * b[5] + a[2] * b[8];

        result[3] = a[3] * b[0] + a[4] * b[3] + a[5] * b[6];
        result[4] = a[3] * b[1] + a[4] * b[4] + a[5] * b[7];
        result[5] = a[3] * b[2] + a[4] * b[5] + a[5] * b[8];

        result[6] = a[6] * b[0] + a[7] * b[3] + a[8] * b[6];
        result[7] = a[6] * b[1] + a[7] * b[4] + a[8] * b[7];
        result[8] = a[6] * b[2] + a[7] * b[5] + a[8] * b[8];

        return result;
    }

    /* Initializing the variables for the data input */
    private void initMaths() {

        acceleration = new float[3];
        magnetic = new float[3];

        initialRotationMatrix = new float[9];

        deltaRotationVector = new float[4];
        deltaRotationMatrix = new float[9];
        currentRotationMatrix = new float[9];
        gyroscopeOrientation = new float[3];

        // Initialize the current rotation matrix as an identity matrix...
        currentRotationMatrix[0] = 1.0f;
        currentRotationMatrix[4] = 1.0f;
        currentRotationMatrix[8] = 1.0f;
    }

    /* Initializing interface elements */
    private void initUI() {
        /* Decimal formatter for TextViews */
        df = new DecimalFormat("#.##");

        /* Initialize TextViews */
        calibrationX = (TextView) this.findViewById(R.id.calibrationX);
        calibrationY = (TextView) this.findViewById(R.id.calibrationY);
        calibrationZ = (TextView) this.findViewById(R.id.calibrationZ);
    }

    /* Initializing data filters to smooth the sensors input */
    private void initFilters() {
        accelerationFilter = new MeanFilter();
        accelerationFilter.setWindowSize(MEAN_FILTER_WINDOW);

        magneticFilter = new MeanFilter();
        magneticFilter.setWindowSize(MEAN_FILTER_WINDOW);
    }

    /* Initializing sensors managers */
    private void initSensors() {
        mSensorManager = (SensorManager) this.getSystemService(Context.SENSOR_SERVICE);
        fusedGyroscopeSensor = new FusedGyroscopeSensor();
    }

    /* Unregister all listeners & clening data */
    private void unregisterListeners(){

        mSensorManager.unregisterListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));

        mSensorManager.unregisterListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));

        if (!useFusedEstimation)
        {
            mSensorManager.unregisterListener(this,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE));
        }

        if (useFusedEstimation)
        {
            mSensorManager.unregisterListener(fusedGyroscopeSensor,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY));

            mSensorManager.unregisterListener(fusedGyroscopeSensor,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));

            mSensorManager.unregisterListener(fusedGyroscopeSensor,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));

            mSensorManager.unregisterListener(fusedGyroscopeSensor,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE));

            fusedGyroscopeSensor.removeObserver(this);
        }

        initMaths();

        accelerationSampleCount = 0;
        magneticSampleCount = 0;

        hasInitialOrientation = false;
        stateInitializedCalibrated = false;
    }

    /* Registering the sensors listeners & start monitoring data*/
    private void registerListeners() {

        mSensorManager.registerListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_FASTEST);

        mSensorManager.registerListener(this,
                mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                SensorManager.SENSOR_DELAY_FASTEST);

        /* If we don't use the FusedSensor*/
        if (!useFusedEstimation)
        {
            mSensorManager.registerListener(this,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_FASTEST);

        }

        /* If we use the FusedSensor*/
        if (useFusedEstimation)
        {
            boolean hasGravity = mSensorManager.registerListener(
                    fusedGyroscopeSensor, mSensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY),
                    SensorManager.SENSOR_DELAY_FASTEST);

            /* If the device does not have a gravity sensor, fall back */
            if (!hasGravity)
            {
                mSensorManager.registerListener(fusedGyroscopeSensor,
                        mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                        SensorManager.SENSOR_DELAY_FASTEST);
            }

            mSensorManager.registerListener(fusedGyroscopeSensor,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
                    SensorManager.SENSOR_DELAY_FASTEST);

            mSensorManager.registerListener(fusedGyroscopeSensor,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE),
                    SensorManager.SENSOR_DELAY_FASTEST);

            fusedGyroscopeSensor.registerObserver(this);
        }
    }

    /* Read user preferences */
    private void readPrefs() {
        SharedPreferences prefs = PreferenceManager
                .getDefaultSharedPreferences(this);

        useFusedEstimation = prefs.getBoolean(ConfigActivity.FUSION_PREFERENCE,
                false);

        Log.d(tag, "Fusion: " + String.valueOf(useFusedEstimation));
    }

    /* Calculates the initial device orientation, need only once */
    private void calculateOrientation() {
        hasInitialOrientation = SensorManager.getRotationMatrix(
                initialRotationMatrix, null, acceleration, magnetic);

        /* Remove the sensor observers since they are no longer required. */
        if (hasInitialOrientation)
        {
            mSensorManager.unregisterListener(this,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
            mSensorManager.unregisterListener(this,
                    mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));
        }
    }

    /* ESTADOS DE LA APLICACION */

    public void onResume(){
        super.onResume();

        readPrefs();

        registerListeners();
    }

    public void onPause(){
        super.onPause();

        unregisterListeners();
    }
}
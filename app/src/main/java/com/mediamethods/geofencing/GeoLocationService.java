package com.mediamethods.geofencing;

import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.onesignal.OneSignal;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class GeoLocationService extends Service implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, LocationListener {

    private static long UPDATE_INTERVAL_IN_MILLISECONDS = 5000;
    private static long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;

    private String TAG = this.getClass().getName();

    protected GoogleApiClient mGoogleApiClient;
    protected LocationRequest mLocationRequest;
    protected Location mCurrentLocation;

    private String mOneSignalUserId = "";
    private String mOneSignalAppRegId = "";

    private final IBinder mBinder = new LocalBinder();

    public GeoLocationService() {
    }

    public class LocalBinder extends Binder {
        GeoLocationService getService() {
            // Return this instance of LocalService so clients can call public methods
            return GeoLocationService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public Location getLatestLocation() {
        return mCurrentLocation;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // initialize OneSignal library
        OneSignal.startInit(this).init();
        OneSignal.enableNotificationsWhenActive(true);

        // get the user id
        OneSignal.idsAvailable(new OneSignal.IdsAvailableHandler() {
            @Override
            public void idsAvailable(String userId, String registrationId) {
                mOneSignalUserId = userId;
                mOneSignalAppRegId = registrationId;
            }
        });

        // initialize google location service
        buildGoogleApiClient();
        mGoogleApiClient.connect();
    }

    protected synchronized void buildGoogleApiClient() {
        Log.i(TAG, "Building GoogleApiClient");
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();

        createLocationRequest();
    }

    protected void createLocationRequest() {
        mLocationRequest = new LocationRequest();

        // Sets the desired interval for active location updates. This interval is
        // inexact. You may not receive updates at all if no location sources are available, or
        // you may receive them slower than requested. You may also receive updates faster than
        // requested if other applications are requesting location at a faster interval.
        mLocationRequest.setInterval(UPDATE_INTERVAL_IN_MILLISECONDS);

        // Sets the fastest rate for active location updates. This interval is exact, and your
        // application will never receive updates faster than this value.
        mLocationRequest.setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS);

        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");

        mGoogleApiClient.disconnect();
    }

    @Override
    public void onConnected(Bundle bundle) {
        LocationServices.FusedLocationApi.requestLocationUpdates(
                mGoogleApiClient, mLocationRequest, this);

        if (mCurrentLocation == null) {

            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                return;
            }

            mCurrentLocation = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        }
    }

    @Override
    public void onConnectionSuspended(int i) {
        mGoogleApiClient.connect();
    }

    @Override
    public void onLocationChanged(Location location) {
        mCurrentLocation = location;

        // window service to see within geofencing area
        if (mOneSignalUserId != "") {
            Runnable r = new LocationUpdateAjaxThread(this, mOneSignalUserId, location.getLongitude(), location.getLatitude(), location.getSpeed());
            new Thread(r).start();
        }
    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {
        Log.e(TAG, "Connection failed: ConnectionResult.getErrorCode() = " + connectionResult.getErrorCode());
    }

    public class LocationUpdateAjaxThread implements Runnable {

        private String userId;
        private double longitude;
        private double latitude;
        private double speed;
        private LocationListener listener;

        public LocationUpdateAjaxThread(LocationListener listener, String userId, double longitude, double latitude, double speed) {
            // store parameter for later user
            this.userId = userId;
            this.longitude = longitude;
            this.latitude = latitude;
            this.speed = speed;
            this.listener = listener;
        }

        public void run() {

            try {

                URL url = new URL("http://api.1.name.my/GeoLocationService.svc/UpdateLocation");
                HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setDoOutput(true);
                urlConnection.setRequestProperty("Content-Type", "application/json");

                OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                writeStream(out, userId, longitude, latitude, speed);
                out.flush();
                out.close();

                InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                String result = readStream(in);
                Log.d(TAG, result);

                urlConnection.disconnect();

                JSONObject jsonRootObject = new JSONObject(result);
                String actualResult = jsonRootObject.optString("UpdateLocationResult").toString();
                JSONObject resultObject = new JSONObject(actualResult);
                JSONObject payloadObject = new JSONObject(resultObject.optString("payload").toString());

                float distance = Float.parseFloat(payloadObject.optString("distanceToClosest").toString());
                if (distance < 500) {
                    UPDATE_INTERVAL_IN_MILLISECONDS = 100;
                } else if (distance < 1000) {
                    UPDATE_INTERVAL_IN_MILLISECONDS = 500;
                } else if (distance < 5000) {
                    UPDATE_INTERVAL_IN_MILLISECONDS = 30000;
                } else if (distance < 20000) {
                    UPDATE_INTERVAL_IN_MILLISECONDS = 120000;
                } else {
                    UPDATE_INTERVAL_IN_MILLISECONDS = 300000;
                }


                if (FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS != UPDATE_INTERVAL_IN_MILLISECONDS / 2) {
                    // previous setting changed
                    FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2;

                    // restart the location update
                    LocationServices.FusedLocationApi.removeLocationUpdates(mGoogleApiClient, listener);
                    LocationServices.FusedLocationApi.requestLocationUpdates(
                            mGoogleApiClient, mLocationRequest, listener);
                }


            } catch (Exception ex) {
                Log.d(TAG, ex.getMessage());
            }
        }

        private void writeStream(OutputStream out, String userId, double longitude, double latitude, double speed) {
            try {
                JSONObject gp = new JSONObject();
                gp.put("id", userId);
                gp.put("longitude", longitude);
                gp.put("latitude", latitude);
                //gp.put("speed", speed);

                out.write(gp.toString().getBytes());

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        private String readStream(InputStream is) {
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                int i = is.read();
                while(i != -1) {
                    bo.write(i);
                    i = is.read();
                }
                return bo.toString();
            } catch (Exception e) {
                return "";
            }
        }
    }
}

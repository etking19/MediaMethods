package com.mediamethods.geofencing;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.FragmentActivity;
import android.os.Bundle;
import android.util.Log;

import com.google.android.gms.maps.CameraUpdate;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.CircleOptions;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MapsActivity extends FragmentActivity implements OnMapReadyCallback, ServiceConnection {

    private GoogleMap mMap = null;
    private GeoLocationService mService = null;

    private Marker mCurrentPosMarker = null;
    private Handler mHandler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_maps);
        // Obtain the SupportMapFragment and get notified when the map is ready to be used.
        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager()
                .findFragmentById(R.id.map);
        mapFragment.getMapAsync(this);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // bind the service
        bindService(new Intent(this, GeoLocationService.class), this, Context.BIND_AUTO_CREATE);

    }

    @Override
    protected void onStop() {
        super.onStop();

        if(mService != null) {
            unbindService(this);
            mService = null;
        }
    }
    /**
     * Manipulates the map once available.
     * This callback is triggered when the map is ready to be used.
     * This is where we can add markers or lines, add listeners or move the camera. In this case,
     * we just add a marker near Sydney, Australia.
     * If Google Play services is not installed on the device, the user will be prompted to install
     * it inside the SupportMapFragment. This method will only be triggered once the user has
     * installed Google Play services and returned to the app.
     */
    @Override
    public void onMapReady(GoogleMap googleMap) {
        mMap = googleMap;

        // Add a marker in Sydney and move the camera
        LatLng sydney = new LatLng(3.011233, 101.670246);
        mCurrentPosMarker = mMap.addMarker(new MarkerOptions().position(sydney).title("Your position"));
        mMap.moveCamera(CameraUpdateFactory.newLatLng(sydney));
        CameraUpdate zoom=CameraUpdateFactory.zoomTo(15);
        mMap.animateCamera(zoom);

        // query from service to get the location
        new Thread(new Runnable() {
            public void run() {
                try {
                    URL url = new URL("http://api.1.name.my/GeoLocationService.svc/GetAllTargets");
                    HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setDoOutput(true);
                    urlConnection.setRequestProperty("Content-Type", "application/json");

                    /*
                    OutputStream out = new BufferedOutputStream(urlConnection.getOutputStream());
                    writeStream(out, userId, longitude, latitude, speed);
                    out.flush();
                    out.close();
                    */

                    InputStream in = new BufferedInputStream(urlConnection.getInputStream());
                    String result = readStream(in);

                    JSONObject jsonRootObject = new JSONObject(result);
                    String actualResult = jsonRootObject.optString("GetAllTargetsResult").toString();
                    JSONObject resultObject = new JSONObject(actualResult);
                    JSONArray payloadObject = new JSONArray(resultObject.optString("payload").toString());

                    //JSONArray jsonArray = payloadObject.optJSONArray("locationList");
                    for(int i=0; i < payloadObject.length(); i++){
                        JSONObject jsonObject = payloadObject.getJSONObject(i);

                        final float latitude = Float.parseFloat(jsonObject.optString("latitude").toString());
                        final float longitude = Float.parseFloat(jsonObject.optString("longitude").toString());
                        final int radius = Integer.parseInt(jsonObject.optString("radius").toString());
                        final String name = jsonObject.optString("name").toString();

                        mHandler.post(new Runnable() {
                            public void run() {
                                mMap.addCircle(new CircleOptions()
                                        .center(new LatLng(latitude, longitude))
                                        .radius(radius)
                                        .strokeColor(Color.RED));

                                mMap.addMarker(new MarkerOptions().position(new LatLng(latitude, longitude)).title(name));
                            }
                        });


                    }

                    urlConnection.disconnect();
                } catch (Exception ex) {
                    Log.d("result", ex.getMessage());
                }

            }
        }).start();


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

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        GeoLocationService.LocalBinder binder = (GeoLocationService.LocalBinder) service;
        mService = binder.getService();

        new Thread(new Runnable() {
            public void run() {
                while(mService != null) {
                    new UpdateLocAsyncTask().execute(mService);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

            }
        }).start();
    }

    private class UpdateLocAsyncTask extends AsyncTask<GeoLocationService, Void, Location> {

        @Override
        protected Location doInBackground(GeoLocationService... params) {
            return params[0].getLatestLocation();
        }

        @Override
        protected void onPostExecute(Location result) {
            if(result != null && mMap != null) {

                final LatLng updatedLoc = new LatLng(result.getLatitude(), result.getLongitude());
                mCurrentPosMarker.setPosition(updatedLoc);

               // CameraUpdate center= CameraUpdateFactory.newLatLng(updatedLoc);
               // CameraUpdate zoom=CameraUpdateFactory.zoomTo(15);

                //mMap.moveCamera(center);
                //mMap.animateCamera(zoom);
            }
        }
    }


    @Override
    public void onServiceDisconnected(ComponentName name) {
        mService = null;
    }
}

/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.androidtv.coursera.ui;

import android.annotation.TargetApi;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v17.leanback.app.VideoFragment;
import android.support.v17.leanback.app.VideoFragmentGlueHost;
import android.support.v17.leanback.widget.ArrayObjectAdapter;
import android.support.v17.leanback.widget.ClassPresenterSelector;
import android.support.v17.leanback.widget.HeaderItem;
import android.support.v17.leanback.widget.ListRow;
import android.support.v17.leanback.widget.ListRowPresenter;
import android.support.v17.leanback.widget.OnItemViewClickedListener;
import android.support.v17.leanback.widget.Presenter;
import android.support.v17.leanback.widget.Row;
import android.support.v17.leanback.widget.RowPresenter;
import android.support.v17.leanback.widget.SparseArrayObjectAdapter;
import android.util.Log;

import com.androidtv.coursera.R;
import com.androidtv.coursera.model.Playlist;
import com.androidtv.coursera.model.Course;
import com.androidtv.coursera.player.VideoPlayerGlue;
import com.androidtv.coursera.Utils;
import com.androidtv.coursera.presenter.EpisodePresenter;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.leanback.LeanbackPlayerAdapter;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

import static android.os.SystemClock.sleep;


/**
 * Plays selected Course, loads playlist and related Courses, and delegates playback to {@link
 * VideoPlayerGlue}.
 */
public class PlaybackFragment extends VideoFragment {

    private static final int UPDATE_DELAY = 16;

    private VideoPlayerGlue mPlayerGlue;
    private LeanbackPlayerAdapter mPlayerAdapter;
    private SimpleExoPlayer mPlayer;
    private TrackSelector mTrackSelector;
    private PlaylistActionListener mPlaylistActionListener;
    private Context mContext;
    private Utils mUtils;
    private Course mCourse;
    private Playlist mPlaylist;
    private SparseArrayObjectAdapter mEpisodeActionAdapter;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = getActivity().getApplicationContext();
        mCourse = getActivity().getIntent().getParcelableExtra("Course");
        mUtils = new Utils(mContext,getActivity().getIntent().getStringExtra("UserId"),getActivity().getIntent().getStringExtra("Cookies"));
        mPlaylist = new Playlist();
        new Thread(netGetLectures).start();
    }

    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            Bundle data = msg.getData();
            String js = data.getString("jsonstring");
            String vu = data.getString("videourl");
            if (js!=null) {
                Log.d("js",js);
                try {
                    JSONArray ja = new JSONObject(js).getJSONArray("re");
                    if (ja.length()>0) {
                        for (Integer i = 0; i < ja.length(); i++) {
                            JSONObject jo = ja.getJSONObject(i);
                            mPlaylist.add(Course.CourseBuilder.buildFromExtra(mCourse.id, mCourse.title, jo.optString("name"), jo.optString("module"), jo.optString("courseitemid"), ""));
                        }
                        mEpisodeActionAdapter = setupEpisodesCourses();
                        ArrayObjectAdapter mRowsAdapter = initializeEpisodesRow();
                        setAdapter(mRowsAdapter);
                        play(mCourse);
                    } else {
                        getActivity().getFragmentManager().popBackStack();
                        getActivity().finish();
                    }
                } catch (Exception e) {
                    Log.e("FetchEpisodeFailed", "Get episodes failed");
                    getActivity().getFragmentManager().popBackStack();
                    getActivity().finish();
                    //e.printStackTrace();
                }
            }
            if (vu!=null) {
                Log.d("vu",vu);
                prepareMediaForPlaying(Uri.parse(vu));
                mPlayerGlue.play();
            }
        }
    };

    Runnable netGetLectures = new Runnable() {
        @Override
        public void run() {
            try {
                while (Utils.getUserId()==null|| Utils.getCookieString()==null) {
                    sleep(1000);
                }
                String js = Utils.getLecturesByCourse(mContext,mCourse);
                Message msg = new Message();
                Bundle data = new Bundle();
                data.putString("jsonstring",js);
                msg.setData(data);
                handler.sendMessage(msg);
            } catch (Exception e) {
                Log.e("Fetch Lectures", "Failed");
            }
        }
    };

    class netGetVideoUrl implements Runnable{
        Course vCourse;
        netGetVideoUrl(Course c) {vCourse=c;}
        public void run() {
            try {
                if (vCourse.cardImageUrl!="") {
                    vCourse = mPlaylist.getFirstCourse();
                }
                while (Utils.getUserId()==null) {
                    sleep(1000);
                }
                JSONObject jsObj = new JSONObject(Utils.getVideoUrl(mContext,vCourse));
                String vurl = jsObj.optString("re").split(",")[0];
                Message msg = new Message();
                Bundle data = new Bundle();
                data.putString("videourl",vurl);
                msg.setData(data);
                handler.sendMessage(msg);
            } catch (Exception e) {
                Log.e("Get Video Url", "Failed");
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        if (Util.SDK_INT > 23) {
            initializePlayer();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if ((Util.SDK_INT <= 23 || mPlayer == null)) {
            initializePlayer();
        }
    }

    /** Pauses the player. */
    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public void onPause() {
        super.onPause();

        if (mPlayerGlue != null && mPlayerGlue.isPlaying()) {
            mPlayerGlue.pause();
        }
        if (Util.SDK_INT <= 23) {
            releasePlayer();
        }
    }

    @Override
    public void onStop() {
        super.onStop();
        if (Util.SDK_INT > 23) {
            releasePlayer();
        }
    }

    private void initializePlayer() {
        BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        TrackSelection.Factory CourseTrackSelectionFactory =
                new AdaptiveTrackSelection.Factory(bandwidthMeter);
        mTrackSelector = new DefaultTrackSelector(CourseTrackSelectionFactory);

        mPlayer = ExoPlayerFactory.newSimpleInstance(getActivity(), mTrackSelector);
        mPlayerAdapter = new LeanbackPlayerAdapter(getActivity(), mPlayer, UPDATE_DELAY);
        mPlaylistActionListener = new PlaylistActionListener(mPlaylist);
        mPlayerGlue = new VideoPlayerGlue(getActivity(), mPlayerAdapter, mPlaylistActionListener);
        mPlayerGlue.setHost(new VideoFragmentGlueHost(this));
        mPlayerGlue.playWhenPrepared();


    }

    private void releasePlayer() {
        if (mPlayer != null) {
            mPlayer.release();
            mPlayer = null;
            mTrackSelector = null;
            mPlayerGlue = null;
            mPlayerAdapter = null;
            mPlaylistActionListener = null;
        }
    }

    private void play(Course vCourse) {
        try {
            new Thread(new netGetVideoUrl(vCourse)).start();
            mPlayerGlue.setTitle(vCourse.title);
        } catch (Exception e) {
            mPlayerGlue.pause();
        }
    }

    private void prepareMediaForPlaying(Uri mediaSourceUri) {
        String userAgent = Util.getUserAgent(getActivity(), "VideoPlayerGlue");
        MediaSource mediaSource =
                new HlsMediaSource(
                        mediaSourceUri,
                        new DefaultDataSourceFactory(getActivity(), userAgent),
                        //new DefaultExtractorsFactory(),
                        null,
                        null);

        mPlayer.prepare(mediaSource);
    }

    private ArrayObjectAdapter initializeEpisodesRow() {
        ClassPresenterSelector presenterSelector = new ClassPresenterSelector();
        presenterSelector.addClassPresenter(
                mPlayerGlue.getControlsRow().getClass(), mPlayerGlue.getPlaybackRowPresenter());
        presenterSelector.addClassPresenter(ListRow.class, new ListRowPresenter());
        ArrayObjectAdapter rowsAdapter = new ArrayObjectAdapter(presenterSelector);
        rowsAdapter.add(mPlayerGlue.getControlsRow());
        HeaderItem header = new HeaderItem(getString(R.string.episodes));
        ListRow row = new ListRow(header, mEpisodeActionAdapter);
        rowsAdapter.add(row);
        setOnItemViewClickedListener(new ItemViewClickedListener());

        return rowsAdapter;
    }

    private SparseArrayObjectAdapter setupEpisodesCourses() {
        int playlistPositionBackup = mPlaylist.getCurrentPosition();
        SparseArrayObjectAdapter episodeCoursesAdapter = new SparseArrayObjectAdapter(new EpisodePresenter());
        episodeCoursesAdapter.set(0, mPlaylist.getFirstCourse());
        mPlaylist.setCurrentPosition(0);
        for (int i=1;i < (mPlaylist.size());i++) {
            episodeCoursesAdapter.set(i, mPlaylist.next());
        }
        mPlaylist.setCurrentPosition(playlistPositionBackup);
        return episodeCoursesAdapter;
    }

    public void skipToNext() {
        mPlayerGlue.next();
    }

    public void skipToPrevious() {
        mPlayerGlue.previous();
    }

    public void rewind() {
        mPlayerGlue.rewind();
    }

    public void fastForward() {
        mPlayerGlue.fastForward();
    }

    /** Opens the Course details page when a related Course has been clicked. */
    private final class ItemViewClickedListener implements OnItemViewClickedListener {
        @Override
        public void onItemClicked(
                Presenter.ViewHolder itemViewHolder,
                Object item,
                RowPresenter.ViewHolder rowViewHolder,
                Row row) {

            if (item instanceof Course) {
                Course vCourse = (Course) item;
                mPlaylist.setCurrentPosition(mPlaylist.getCoursePosition(vCourse));
                play(vCourse);
            }
        }
    }

    class PlaylistActionListener implements VideoPlayerGlue.OnActionClickedListener {

        private Playlist mPlaylist;

        PlaylistActionListener(Playlist playlist) {
            this.mPlaylist = playlist;
        }

        @Override
        public void onPrevious() {
            play(mPlaylist.previous());
        }

        @Override
        public void onNext() {
            play(mPlaylist.next());
        }

        public void onReverse() {
            mPlaylist.reverse();
            mEpisodeActionAdapter = setupEpisodesCourses();
            ArrayObjectAdapter mRowsAdapter = initializeEpisodesRow();
            setAdapter(mRowsAdapter);
        }
    }
}

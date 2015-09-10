/*
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package ch.blinkenlights.android.vanilla;

import android.app.PendingIntent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.provider.MediaStore;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.media.MediaBrowserService;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Handles music playback through the MediaBrowserService.
 */
public class MusicService extends MediaBrowserService {

	private static final String TAG = "MusicService";
	// Action to change the repeat mode
	private static final String CUSTOM_ACTION_REPEAT = "ch.blinkenlights.android.vanilla.REPEAT";
	// Action to change the repeat mode
	private static final String CUSTOM_ACTION_SHUFFLE = "ch.blinkenlights.android.vanilla.SHUFFLE";

    // Separators used to build MediaIDs for the MediaBrowserService
	public static final String MEDIATYPE_SEPARATOR = "/";
	public static final String FILTER_SEPARATOR = "#";
	public static final String ID_TYPE_ROOT = Integer.toString(MediaUtils.TYPE_INVALID);

	// Music catalog manager
	private MediaStoreWrapper mArtistWrapper;
	private MediaStoreWrapper mAlbumWrapper;
	private MediaStoreWrapper mSongWrapper;
	private MediaStoreWrapper mPlaylistWrapper;
	private MediaStoreWrapper mGenreWrapper;
	private MediaStoreWrapper[] mMediaWrappers = new MediaStoreWrapper[MediaUtils.TYPE_GENRE + 1];
	private List<MediaBrowser.MediaItem> mAlbums = new ArrayList<MediaBrowser.MediaItem>();
	private List<MediaBrowser.MediaItem> mArtists = new ArrayList<MediaBrowser.MediaItem>();
	private List<MediaBrowser.MediaItem> mSongs = new ArrayList<MediaBrowser.MediaItem>();
	private List<MediaBrowser.MediaItem> mPlaylists = new ArrayList<MediaBrowser.MediaItem>();
	private List<MediaBrowser.MediaItem> mGenres = new ArrayList<MediaBrowser.MediaItem>();
	private List<MediaBrowser.MediaItem> mFiltered = new ArrayList<MediaBrowser.MediaItem>();
	private boolean mCatalogReady = false;

	private final List<MediaBrowser.MediaItem> mMediaRoot = new ArrayList<MediaBrowser.MediaItem>();

	// Media Session
	private MediaSession mSession;
	private Bundle mSessionExtras;

	// Indicates whether the service was started.
	private boolean mServiceStarted;

	/*
	 * (non-Javadoc)
	 * @see android.app.Service#onCreate()
	 */
	@Override
	public void onCreate() {
		Log.d("VanillaMusic", "onCreate");
		super.onCreate();

		// Prep the Music Catalog (caches the main categories)
		mArtistWrapper = new MediaStoreWrapper(this, MediaUtils.TYPE_ARTIST, null);
		mAlbumWrapper = new MediaStoreWrapper(this, MediaUtils.TYPE_ALBUM, null);
		mSongWrapper = new MediaStoreWrapper(this, MediaUtils.TYPE_SONG, null);
		mPlaylistWrapper = new MediaStoreWrapper(this, MediaUtils.TYPE_PLAYLIST, null);
		mGenreWrapper = new MediaStoreWrapper(this, MediaUtils.TYPE_GENRE, null);
		mMediaWrappers[MediaUtils.TYPE_ARTIST] = mArtistWrapper;
		mMediaWrappers[MediaUtils.TYPE_ALBUM] = mAlbumWrapper;
		mMediaWrappers[MediaUtils.TYPE_SONG] = mSongWrapper;
		mMediaWrappers[MediaUtils.TYPE_PLAYLIST] = mPlaylistWrapper;
		mMediaWrappers[MediaUtils.TYPE_GENRE] = mGenreWrapper;

		// Fill and cache the root queries

		mMediaRoot.add(new MediaBrowser.MediaItem(
				new MediaDescription.Builder()
					.setMediaId(Integer.toString(MediaUtils.TYPE_ARTIST))
					.setTitle(getString(R.string.artists))
					.setIconUri(Uri.parse("android.resource://" +
							"ch.blinkenlights.android.vanilla/drawable/ic_menu_music_library"))
					.setSubtitle(getString(R.string.artists))
					.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
		));

		mMediaRoot.add(new MediaBrowser.MediaItem(
				new MediaDescription.Builder()
					.setMediaId(Integer.toString(MediaUtils.TYPE_ALBUM))
					.setTitle(getString(R.string.albums))
					.setIconUri(Uri.parse("android.resource://" +
							"ch.blinkenlights.android.vanilla/drawable/ic_menu_music_library"))
					.setSubtitle(getString(R.string.albums))
					.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
		));

		mMediaRoot.add(new MediaBrowser.MediaItem(
				new MediaDescription.Builder()
					.setMediaId(Integer.toString(MediaUtils.TYPE_SONG))
					.setTitle(getString(R.string.songs))
					.setIconUri(Uri.parse("android.resource://" +
							"ch.blinkenlights.android.vanilla/drawable/ic_menu_music_library"))
					.setSubtitle(getString(R.string.songs))
					.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
		));

		mMediaRoot.add(new MediaBrowser.MediaItem(
				new MediaDescription.Builder()
					.setMediaId(Integer.toString(MediaUtils.TYPE_GENRE))
					.setTitle(getString(R.string.genres))
					.setIconUri(Uri.parse("android.resource://" +
							"ch.blinkenlights.android.vanilla/drawable/ic_menu_music_library"))
					.setSubtitle(getString(R.string.genres))
					.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
		));

		mMediaRoot.add(new MediaBrowser.MediaItem(
				new MediaDescription.Builder()
					.setMediaId(Integer.toString(MediaUtils.TYPE_PLAYLIST))
					.setTitle(getString(R.string.playlists))
					.setIconUri(Uri.parse("android.resource://" +
							"ch.blinkenlights.android.vanilla/drawable/ic_menu_music_library"))
					.setSubtitle(getString(R.string.playlists))
					.build(), MediaBrowser.MediaItem.FLAG_BROWSABLE
		));


		// Start a new MediaSession
		mSession = new MediaSession(this, "VanillaMusicService");
		setSessionToken(mSession.getSessionToken());
		mSession.setCallback(new MediaSessionCallback());
		mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);

		Context context = getApplicationContext();
		Intent intent = new Intent(context, FullPlaybackActivity.class);
		PendingIntent pi = PendingIntent.getActivity(context, 99 /*request code*/,
						   intent, PendingIntent.FLAG_UPDATE_CURRENT);
		mSession.setSessionActivity(pi);

		mSessionExtras = new Bundle();
		mSession.setExtras(mSessionExtras);

		PlaybackService.registerService(this);

		// Make sure the PlaybackService is running
		if(!PlaybackService.hasInstance()) {
			Thread t = new Thread(new Runnable() {
				@Override
				public void run() {
					PlaybackService.get(MusicService.this);
				}
			});
			t.start();
		}

		updatePlaybackState(null);
	}

	/**
	 * (non-Javadoc)
	 * @see android.app.Service#onStartCommand(android.content.Intent, int, int)
	 */
	@Override
	public int onStartCommand(Intent startIntent, int flags, int startId) {
		Log.d("VanillaMusic", "onStartCommand");
		return START_STICKY;
	}

	/**
	 * (non-Javadoc)
	 * @see android.app.Service#onDestroy()
	 */
	@Override
	public void onDestroy() {
		Log.d("VanillaMusic", "onDestroy");
		mServiceStarted = false;
		PlaybackService.unregisterService();
		mSession.release();
	}

	/**
	 * Media Catalog handling
	 */
	private static int typeFromMediaId(String mediaID) {
		String[] items = mediaID.split(MEDIATYPE_SEPARATOR);
		return items.length > 0 ? Integer.parseInt(items[0]) : MediaUtils.TYPE_INVALID;
	}

	private static boolean isBasicType(String mediaID) {
		return mediaID.indexOf(MEDIATYPE_SEPARATOR) == -1;
	}

	private static Limiter limiterFromMediaId(String mediaID) {
		int mediaType = typeFromMediaId(mediaID);
		String[] items = mediaID.split(MEDIATYPE_SEPARATOR);
		String label = "";
		long id = -1;
		if(items.length > 1) {
			items = items[1].split(FILTER_SEPARATOR);
		}
		if(items.length != 2) {
		   items = null;
		} else {
			label = items[1];
			id = Long.parseLong(items[0]);
		}
		Limiter limiter = null;
		String[] fields;
		Object data;
		boolean isBasicType = isBasicType(mediaID);
		switch(mediaType) {
			case MediaUtils.TYPE_ARTIST:
				if(isBasicType || items == null) {
					limiter = new Limiter(MediaUtils.TYPE_ARTIST, null, null);
				} else {
					fields = new String[] { label };
					data = String.format("%s=%d", MediaStore.Audio.Media.ARTIST_ID, id);
					limiter = new Limiter(MediaUtils.TYPE_ARTIST, fields, data);
				}
				// build a album limited by artist
			break;
			case MediaUtils.TYPE_ALBUM:
				if(isBasicType || items == null) {
					limiter = new Limiter(MediaUtils.TYPE_ALBUM, null, null);
				} else {
					fields = new String[] { label };
					data = String.format("%s=%d",  MediaStore.Audio.Media.ALBUM_ID, id);
					limiter = new Limiter(MediaUtils.TYPE_SONG, fields, data);
				}
				// build a song limited by album
			break;
			case MediaUtils.TYPE_SONG:
				if(isBasicType || items == null) {
					limiter = new Limiter(MediaUtils.TYPE_SONG, null, null);
				} else {

				}
				// don't build much
			break;
			case MediaUtils.TYPE_GENRE:
				// build an artist limiter by genere
				if(isBasicType || items == null) {
					limiter = new Limiter(MediaUtils.TYPE_GENRE, null, null);
				} else {
					fields = new String[] { label };
					data = id;
					limiter = new Limiter(MediaUtils.TYPE_GENRE, fields, data);
				}
			break;
			case MediaUtils.TYPE_PLAYLIST:
				if(isBasicType || items == null) {
					limiter = new Limiter(MediaUtils.TYPE_PLAYLIST, null, null);
				} else {

				}
			break;
			case MediaUtils.TYPE_INVALID:
			break;
		}
		return limiter;
	}

	private QueryTask buildQuery(String mediaID, boolean empty, boolean all)
	{
		int mediaType = typeFromMediaId(mediaID);
		String[] items = mediaID.split(MEDIATYPE_SEPARATOR);
		if(items.length > 1) {
			items = items[1].split(FILTER_SEPARATOR);
		}
		if(items.length != 2) {
			return null;
		}

		final long id = Long.parseLong(items[0]);
		String[] projection;

		if (mediaType == MediaUtils.TYPE_PLAYLIST)
			projection = empty ? Song.EMPTY_PLAYLIST_PROJECTION : Song.FILLED_PLAYLIST_PROJECTION;
		else
			projection = empty ? Song.EMPTY_PROJECTION : Song.FILLED_PROJECTION;

		QueryTask query;
		if (all && (mediaType != MediaUtils.TYPE_PLAYLIST)) {
			query = (mMediaWrappers[mediaType]).buildSongQuery(projection);
			query.data = id;
			query.mode = SongTimeline.MODE_PLAY_ID_FIRST;
		} else {
			query = MediaUtils.buildQuery(mediaType, id, projection, null);
			query.mode = SongTimeline.MODE_PLAY;
		}

		return query;
	}

	private void loadChildrenAsync( final String parentMediaId,
									final Result<List<MediaItem>> result) {

		// Asynchronously load the music catalog in a separate threader
		final Limiter limiter = isBasicType(parentMediaId) ? null : limiterFromMediaId(parentMediaId);
		new AsyncTask<Void, Void, Integer>() {
			@Override
			protected Integer doInBackground(Void... params) {
				int result = 0;
				try {
					if(!mCatalogReady) {
						runQuery(mArtists, MediaUtils.TYPE_ARTIST , mArtistWrapper.getQuery());
						runQuery(mAlbums, MediaUtils.TYPE_ALBUM, mAlbumWrapper.getQuery());
						runQuery(mSongs, MediaUtils.TYPE_SONG, mSongWrapper.getQuery());
						runQuery(mGenres, MediaUtils.TYPE_GENRE, mGenreWrapper.getQuery());
						runQuery(mPlaylists, MediaUtils.TYPE_PLAYLIST, mPlaylistWrapper.getQuery());
						mCatalogReady = true;
					}
					if(limiter != null) {
						mFiltered.clear();
						switch(limiter.type) {
							case MediaUtils.TYPE_ALBUM:
								mSongWrapper.setLimiter(limiter);
								runQuery(mFiltered, MediaUtils.TYPE_SONG, mSongWrapper.getQuery());
							break;
							case MediaUtils.TYPE_ARTIST:
								mAlbumWrapper.setLimiter(limiter);
								runQuery(mFiltered, MediaUtils.TYPE_ALBUM, mAlbumWrapper.getQuery());
							break;
							case MediaUtils.TYPE_SONG:
								mSongWrapper.setLimiter(limiter);
								runQuery(mFiltered, MediaUtils.TYPE_SONG, mSongWrapper.getQuery());
							break;
							case MediaUtils.TYPE_PLAYLIST:
								mPlaylistWrapper.setLimiter(limiter);
								runQuery(mFiltered, MediaUtils.TYPE_PLAYLIST, mPlaylistWrapper.getQuery());
							break;
							case MediaUtils.TYPE_GENRE:
								mSongWrapper.setLimiter(limiter);
								runQuery(mFiltered, MediaUtils.TYPE_SONG, mSongWrapper.getQuery());
							break;
						}
					}
					result = 1;
				} catch (Exception e) {
					Log.d("VanillaMusic","Failed retrieving Media");
				}
				return Integer.valueOf(result);
			}

			@Override
			protected void onPostExecute(Integer current) {
				List<MediaBrowser.MediaItem> items = null;
				if (result != null) {
					items = mFiltered;
					if(isBasicType(parentMediaId)) {
						switch(typeFromMediaId(parentMediaId)) {
							case MediaUtils.TYPE_ALBUM:
								items = mAlbums;
								mAlbumWrapper.setLimiter(null);
							break;
							case MediaUtils.TYPE_ARTIST:
								items = mArtists;
								mArtistWrapper.setLimiter(null);
							break;
							case MediaUtils.TYPE_SONG:
								items = mSongs;
								mSongWrapper.setLimiter(null);
							break;
							case MediaUtils.TYPE_PLAYLIST:
								items = mPlaylists;
								mPlaylistWrapper.setLimiter(null);
							break;
							case MediaUtils.TYPE_GENRE:
								items = mGenres;
								mGenreWrapper.setLimiter(null);
							break;
						}
					}
					if (current == 1) {
						result.sendResult(items);
					} else {
						result.sendResult(Collections.<MediaItem>emptyList());
					}
				}
			}
		}.execute();
	}

	private Uri getArtUri(int mediaType, String mediaId) {
		switch(mediaType) {
			case MediaUtils.TYPE_SONG:
				return Uri.parse("content://media/external/audio/media/" + mediaId + "/albumart");
			case MediaUtils.TYPE_ALBUM:
				return Uri.parse("content://media/external/audio/albumart/" + mediaId);
		}
		return Uri.parse("android.resource://ch.blinkenlights.android.vanilla/drawable/fallback_cover");
	}

	private String  subtitleForMediaType(int mediaType) {
		switch(mediaType) {
			case MediaUtils.TYPE_ARTIST:
				return getString(R.string.artists);
			case MediaUtils.TYPE_SONG:
				return getString(R.string.songs);
			case MediaUtils.TYPE_PLAYLIST:
				return getString(R.string.playlists);
			case MediaUtils.TYPE_GENRE:
				return getString(R.string.genres);
			case MediaUtils.TYPE_ALBUM:
				return getString(R.string.albums);
		}
		return "";
	}

	private void runQuery(List<MediaBrowser.MediaItem> populateMe, int mediaType, QueryTask query) {
		populateMe.clear();
		try {
			Cursor cursor = query.runQuery(getContentResolver());

			if (cursor == null) {
				return;
			}

			final int flags =      (mediaType != MediaUtils.TYPE_SONG)
								&& (mediaType != MediaUtils.TYPE_PLAYLIST) ?
								    MediaBrowser.MediaItem.FLAG_BROWSABLE  : MediaBrowser.MediaItem.FLAG_PLAYABLE;

			final int count = cursor.getCount();

			for (int j = 0; j != count; ++j) {
				cursor.moveToPosition(j);
				final String id = cursor.getString(0);
				final String label = cursor.getString(2);
				MediaBrowser.MediaItem item = new MediaBrowser.MediaItem(
					new MediaDescription.Builder()
						.setMediaId(   Integer.toString(mediaType)
									 + MEDIATYPE_SEPARATOR
									 + id
									 + FILTER_SEPARATOR
									 + label
									 )
						.setTitle(label)
						.setSubtitle(subtitleForMediaType(mediaType))
						.setIconUri(getArtUri(mediaType, id))
						.build(),
						flags);
				populateMe.add(item);
			}

			cursor.close();
		} catch (Exception e) {
			Log.d("VanillaMusic","Failed retrieving Media");
		}
	}

	/*
	 ** MediaBrowserService APIs
	 */

	@Override
	public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
		return new BrowserRoot(ID_TYPE_ROOT, null);
	}

	@Override
	public void onLoadChildren(final String parentMediaId, final Result<List<MediaItem>> result) {
		// Use result.detach to allow calling result.sendResult from another thread:
		result.detach();
		if (!ID_TYPE_ROOT.equals(parentMediaId)) {
			loadChildrenAsync(parentMediaId, result);
		} else {
			result.sendResult(mMediaRoot);
		}
	}

	/*
	 ** MediaSession.Callback
	 */
	private final class MediaSessionCallback extends MediaSession.Callback {

		private void setSessionActive() {
			if (!mServiceStarted) {
				// The MusicService needs to keep running even after the calling MediaBrowser
				// is disconnected. Call startService(Intent) and then stopSelf(..) when we no longer
				// need to play media.
				startService(new Intent(getApplicationContext(), MusicService.class));
				mServiceStarted = true;
			}

			if (!mSession.isActive()) {
				mSession.setActive(true);
			}
		}

		private void setSessionInactive() {
			if(mServiceStarted) {
				// service is no longer necessary. Will be started again if needed.
				MusicService.this.stopSelf();
				mServiceStarted = false;
			}

			if(mSession.isActive()) {
				mSession.setActive(false);
			}
		}

		@Override
		public void onPlay() {
			setSessionActive();

			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).play();
			}
		}

		@Override
		public void onSeekTo(long position) {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).seekToProgress((int) position);
			}
		}

		@Override
		public void onPlayFromMediaId(final String mediaId, Bundle extras) {
			setSessionActive();

			if(PlaybackService.hasInstance()) {
				QueryTask query = buildQuery(mediaId, false, true);
				PlaybackService.get(MusicService.this).addSongs(query);
			}
		}

		@Override
		public void onPause() {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).pause();
			}
		}

		@Override
		public void onStop() {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).pause();
			}
			setSessionInactive();
		}

		@Override
		public void onSkipToNext() {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).shiftCurrentSong(SongTimeline.SHIFT_NEXT_SONG);
			}
		}

		@Override
		public void onSkipToPrevious() {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).shiftCurrentSong(SongTimeline.SHIFT_PREVIOUS_SONG);
			}
		}

		@Override
		public void onFastForward() {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).performAction(Action.SeekForward, null);
			}
		}

		@Override
		public void onRewind() {
			if(PlaybackService.hasInstance()) {
				PlaybackService.get(MusicService.this).performAction(Action.SeekBackward, null);
			}
		}

		@Override
		public void onCustomAction(String action, Bundle extras) {
			if (CUSTOM_ACTION_REPEAT.equals(action)) {
				if(PlaybackService.hasInstance()) {
					PlaybackService.get(MusicService.this).cycleFinishAction();
					updatePlaybackState(null);
				}
			} else if (CUSTOM_ACTION_SHUFFLE.equals(action)) {
				if(PlaybackService.hasInstance()) {
					PlaybackService.get(MusicService.this).cycleShuffle();
					updatePlaybackState(null);
				}
			}
		}
	}

	/**
	 * Update the current media player state, optionally showing an error message.
	 *
	 * @param error if not null, error message to present to the user.
	 */
	private void updatePlaybackState(String error) {
		long position = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
		int state = PlaybackState.STATE_PAUSED;

		if(PlaybackService.hasInstance()) {
			if (PlaybackService.get(this).isPlaying()) {
				state = PlaybackState.STATE_PLAYING;
			}
			position = PlaybackService.get(this).getPosition();
		}

		PlaybackState.Builder stateBuilder = new PlaybackState.Builder()
				.setActions(getAvailableActions());

		setCustomAction(stateBuilder);

		// If there is an error message, send it to the playback state:
		if (error != null) {
			// Error states are really only supposed to be used for errors that cause playback to
			// stop unexpectedly and persist until the user takes action to fix it.
			stateBuilder.setErrorMessage(error);
			state = PlaybackState.STATE_ERROR;
		}
		stateBuilder.setState(state, position, 1.0f, SystemClock.elapsedRealtime());
		mSession.setPlaybackState(stateBuilder.build());

	}

	private static final int[] FINISH_ICONS =
		{ R.drawable.repeat_inactive_service, R.drawable.repeat_active, R.drawable.repeat_current_active, R.drawable.stop_current_active, R.drawable.random_active };

	private static final int[] SHUFFLE_ICONS =
		{ R.drawable.shuffle_inactive_service, R.drawable.shuffle_active, R.drawable.shuffle_active, R.drawable.shuffle_album_active };

	private void setCustomAction(PlaybackState.Builder stateBuilder) {
		if(PlaybackService.hasInstance()) {
			Bundle customActionExtras = new Bundle();
			final int finishMode = PlaybackService.finishAction(PlaybackService.get(this).getState());
			final int shuffleMode = PlaybackService.shuffleMode(PlaybackService.get(this).getState());

			stateBuilder.addCustomAction(new PlaybackState.CustomAction.Builder(
					CUSTOM_ACTION_REPEAT, getString(R.string.cycle_repeat_mode), FINISH_ICONS[finishMode])
					.setExtras(customActionExtras)
					.build());

			stateBuilder.addCustomAction(new PlaybackState.CustomAction.Builder(
					CUSTOM_ACTION_SHUFFLE, getString(R.string.cycle_shuffle_mode), SHUFFLE_ICONS[shuffleMode])
					.setExtras(customActionExtras)
					.build());
		}
	}

	private long getAvailableActions() {
		long actions =   PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
					   | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_NEXT;

		if(PlaybackService.hasInstance()) {
			if (PlaybackService.get(this).isPlaying()) {
				actions |= PlaybackState.ACTION_PAUSE;
				actions |= PlaybackState.ACTION_FAST_FORWARD;
				actions |= PlaybackState.ACTION_REWIND;
			}
		}
		return actions;
	}

	/**
	 * Implementation of the PlaybackService callbacks
	 */
	public void onTimelineChanged() {
		updatePlaybackState(null);
	}

	public void setState(long uptime, int state) {
		updatePlaybackState(null);
	}

	public void setSong(long uptime, Song song) {
		updatePlaybackState(null);
		if(song == null) {
			if(PlaybackService.hasInstance()) {
				song = PlaybackService.get(this).getSong(0);
			}
		}

		if(song != null) {
			MediaMetadata metadata = new MediaMetadata.Builder()
				.putString(MediaMetadata.METADATA_KEY_MEDIA_ID, Long.toString(song.id))
				.putString(MediaMetadata.METADATA_KEY_ALBUM, song.album)
				.putString(MediaMetadata.METADATA_KEY_ARTIST, song.artist)
				.putLong(MediaMetadata.METADATA_KEY_DURATION, song.duration)
				.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, "content://media/external/audio/media/" + Long.toString(song.id) + "/albumart")
				.putString(MediaMetadata.METADATA_KEY_TITLE, song.title)
				.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, song.trackNumber)
				.build();
			mSession.setMetadata(metadata);
		}
	}

	public void onPositionInfoChanged() {
		updatePlaybackState(null);
	}

	public void onError(String error) {
		updatePlaybackState(error);
	}

	public void onMediaChanged() {
		if(PlaybackService.hasInstance()) {
			setSong(0,PlaybackService.get(this).getSong(0));
		}

	}
}

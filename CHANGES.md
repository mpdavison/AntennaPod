# Changes from Upstream AntennaPod

This project is a modified version of AntennaPod, which is licensed under the
GNU General Public License v3.0. Last modified: July 2026.

## Additions

New files not present in upstream:

- `adskip/adskip.py`
- `adskip/requirements.txt`
- `adskip/test_adskip.py`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/AdDetectionPreferencesFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/AdProviderProfileEditorFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/AdProviderProfileListFragment.java`
- `app/src/main/res/layout/ad_provider_profile_editor_fragment.xml`
- `app/src/main/res/layout/ad_provider_profile_list_fragment.xml`
- `app/src/main/res/layout/ad_provider_profile_list_item.xml`
- `docker-compose.yaml`
- `event/src/main/java/de/danoeh/antennapod/event/AdDetectionProgressEvent.java`
- `model/src/test/java/de/danoeh/antennapod/model/feed/FeedPreferencesTest.java`
- `net/common/src/main/java/de/danoeh/antennapod/net/common/NostrClient.java`
- `net/common/src/main/java/de/danoeh/antennapod/net/common/NostrPreferences.java`
- `net/common/src/test/java/de/danoeh/antennapod/net/common/NostrClientTest.java`
- `net/download/service-interface/src/main/java/de/danoeh/antennapod/net/download/serviceinterface/AdDetectionManager.java`
- `net/download/service-interface/src/test/java/de/danoeh/antennapod/net/download/serviceinterface/AdDetectionManagerTest.java`
- `net/download/service/src/main/java/de/danoeh/antennapod/net/download/service/episode/AdDetectionWorker.java`
- `net/download/service/src/test/java/de/danoeh/antennapod/net/download/service/episode/AdDetectionWorkerTest.java`
- `playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/AdProxyTimestampPoller.java`
- `playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/AdSkipController.java`
- `playback/service/src/test/java/de/danoeh/antennapod/playback/service/internal/AdSkipControllerTest.java`
- `run.sh`
- `storage/importexport/src/main/java/de/danoeh/antennapod/storage/importexport/AdSkipPreferencesTransporter.java`
- `storage/importexport/src/test/java/de/danoeh/antennapod/storage/importexport/AdSkipPreferencesTransporterTest.java`
- `storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/AdDetectionPreferences.java`
- `storage/preferences/src/main/java/de/danoeh/antennapod/storage/preferences/AdProviderProfile.java`
- `transcripts/Meta Acquires Moltbook Facebook for AI Bots.vtt`
- `ui/preferences/src/main/res/xml/preferences_ad_detection.xml`

## Modifications

Upstream files that were modified:

- `AGENTS.md`
- `app/build.gradle`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/de/danoeh/antennapod/activity/MainActivity.java`
- `app/src/main/java/de/danoeh/antennapod/ClientConfigurator.java`
- `app/src/main/java/de/danoeh/antennapod/ui/episodeslist/EpisodeItemListAdapter.java`
- `app/src/main/java/de/danoeh/antennapod/ui/episodeslist/EpisodeItemViewHolder.java`
- `app/src/main/java/de/danoeh/antennapod/ui/episodeslist/EpisodesListFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/episodeslist/FeedItemMenuHandler.java`
- `app/src/main/java/de/danoeh/antennapod/ui/episodeslist/HorizontalItemListAdapter.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/download/CompletedDownloadsFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/episode/ItemFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/episode/ItemPagerFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/feed/FeedItemlistFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/feed/preferences/FeedSettingsPreferenceFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/home/sections/EpisodesSurpriseSection.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/home/sections/InboxSection.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/home/sections/QueueSection.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/playback/audio/ItemDescriptionFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/ImportExportPreferencesFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/MainPreferencesFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/preferences/PreferenceActivity.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/queue/QueueFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/screen/SearchFragment.java`
- `app/src/main/java/de/danoeh/antennapod/ui/swipeactions/RemoveFromQueueSwipeAction.java`
- `app/src/main/res/layout/feeditem_fragment.xml`
- `app/src/main/res/layout/item_description_fragment.xml`
- `app/src/main/res/menu/feeditemlist_context.xml`
- `app/src/main/res/menu/feeditem_options.xml`
- `app/src/main/res/xml/feed_settings.xml`
- `common.gradle`
- `.gitignore`
- `model/src/main/java/de/danoeh/antennapod/model/feed/FeedPreferences.java`
- `net/common/build.gradle`
- `net/download/service-interface/build.gradle`
- `net/download/service/src/main/AndroidManifest.xml`
- `net/download/service/src/main/java/de/danoeh/antennapod/net/download/service/episode/EpisodeDownloadWorker.java`
- `net/download/service/src/main/java/de/danoeh/antennapod/net/download/service/episode/MediaDownloadedHandler.java`
- `net/download/service/src/main/java/de/danoeh/antennapod/net/download/service/feed/FeedUpdateWorker.java`
- `net/download/service/src/main/java/de/danoeh/antennapod/net/download/service/feed/remote/HttpDownloader.java`
- `net/download/service/src/main/res/values/ids.xml`
- `playback/base/build.gradle`
- `playback/base/src/main/java/de/danoeh/antennapod/playback/base/MediaItemAdapter.java`
- `playback/service/build.gradle`
- `playback/service/src/main/java/de/danoeh/antennapod/playback/service/internal/ExoPlayerWrapper.java`
- `playback/service/src/main/java/de/danoeh/antennapod/playback/service/Media3PlaybackService.java`
- `README.md`
- `settings.gradle`
- `storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBUpgrader.java`
- `storage/database/src/main/java/de/danoeh/antennapod/storage/database/DBWriter.java`
- `storage/database/src/main/java/de/danoeh/antennapod/storage/database/mapper/FeedPreferencesCursor.java`
- `storage/database/src/main/java/de/danoeh/antennapod/storage/database/PodDBAdapter.java`
- `storage/importexport/build.gradle`
- `ui/common/src/main/java/de/danoeh/antennapod/ui/common/CircularProgressBar.java`
- `ui/common/src/main/res/values/colors.xml`
- `ui/i18n/src/main/res/values/strings.xml`
- `ui/preferences/src/main/res/values/arrays.xml`
- `ui/preferences/src/main/res/xml/preferences_import_export.xml`
- `ui/preferences/src/main/res/xml/preferences.xml`

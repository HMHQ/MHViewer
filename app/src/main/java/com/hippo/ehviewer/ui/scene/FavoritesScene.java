/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene;

import android.animation.Animator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ImageSpan;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.axlecho.api.MHApi;
import com.axlecho.api.MHComicInfo;
import com.axlecho.api.MHPlugin;
import com.axlecho.api.MHPluginManager;
import com.github.amlcurran.showcaseview.ShowcaseView;
import com.github.amlcurran.showcaseview.SimpleShowcaseEventListener;
import com.github.amlcurran.showcaseview.targets.PointTarget;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.hippo.android.resource.AttrResources;
import com.hippo.annotation.Implemented;
import com.hippo.drawable.AddDeleteDrawable;
import com.hippo.drawable.DrawerArrowDrawable;
import com.hippo.easyrecyclerview.EasyRecyclerView;
import com.hippo.easyrecyclerview.FastScroller;
import com.hippo.ehviewer.CheckUpdateService;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.EhDB;
import com.hippo.ehviewer.ImportService;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.client.EhClient;
import com.hippo.ehviewer.client.EhConfig;
import com.hippo.ehviewer.client.EhRequest;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.data.FavListUrlBuilder;
import com.hippo.ehviewer.client.data.GalleryInfo;
import com.hippo.ehviewer.client.parser.FavoritesParser;
import com.hippo.ehviewer.dao.ReadingRecord;
import com.hippo.ehviewer.ui.MainActivity;
import com.hippo.ehviewer.ui.annotation.DrawerLifeCircle;
import com.hippo.ehviewer.ui.annotation.ViewLifeCircle;
import com.hippo.ehviewer.ui.annotation.WholeLifeCircle;
import com.hippo.ehviewer.widget.EhDrawerLayout;
import com.hippo.ehviewer.widget.GalleryInfoContentHelper;
import com.hippo.ehviewer.widget.SearchBar;
import com.hippo.refreshlayout.RefreshLayout;
import com.hippo.ripple.Ripple;
import com.hippo.scene.Announcer;
import com.hippo.scene.SceneFragment;
import com.hippo.util.DrawableManager;
import com.hippo.widget.ContentLayout;
import com.hippo.widget.FabLayout;
import com.hippo.widget.SearchBarMover;
import com.hippo.yorozuya.AnimationUtils;
import com.hippo.yorozuya.AssertUtils;
import com.hippo.yorozuya.ObjectUtils;
import com.hippo.yorozuya.SimpleAnimatorListener;
import com.hippo.yorozuya.SimpleHandler;
import com.hippo.yorozuya.ViewUtils;
import com.orhanobut.logger.Logger;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import io.reactivex.Scheduler;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

// TODO Get favorite, modify favorite, add favorite, what a mess!
public class FavoritesScene extends BaseScene implements
        EasyRecyclerView.OnItemClickListener, EasyRecyclerView.OnItemLongClickListener,
        FastScroller.OnDragHandlerListener, SearchBarMover.Helper, SearchBar.Helper,
        FabLayout.OnClickFabListener, EasyRecyclerView.CustomChoiceListener, FabLayout.OnExpandListener, CheckUpdateService.UpdateListener, RefreshLayout.OnRefreshListener {

    private static final long ANIMATE_TIME = 300L;

    private static final String KEY_URL_BUILDER = "url_builder";
    private static final String KEY_SEARCH_MODE = "search_mode";
    private static final String KEY_HAS_FIRST_REFRESH = "has_first_refresh";
    private static final String KEY_FAV_COUNT_ARRAY = "fav_count_array";

    @Nullable
    @ViewLifeCircle
    private EasyRecyclerView mRecyclerView;
    @Nullable
    @ViewLifeCircle
    private SearchBar mSearchBar;
    @Nullable
    @ViewLifeCircle
    private FabLayout mFabLayout;
    @Nullable
    private RadioGroup mSourceBar;

    @Nullable
    @ViewLifeCircle
    private FavoritesAdapter mAdapter;
    @Nullable
    @ViewLifeCircle
    private FavoritesHelper mHelper;
    @Nullable
    @ViewLifeCircle
    private SearchBarMover mSearchBarMover;
    @Nullable
    @ViewLifeCircle
    private DrawerArrowDrawable mLeftDrawable;

    @Nullable
    private EhDrawerLayout mDrawerLayout;

    @Nullable
    @DrawerLifeCircle
    private FavDrawerAdapter mDrawerAdapter;
    @Nullable
    @WholeLifeCircle
    private EhClient mClient;
    @Nullable
    @WholeLifeCircle
    private String[] mFavCatArray;
    @Nullable
    @WholeLifeCircle
    private int[] mFavCountArray;
    @Nullable
    @WholeLifeCircle
    private FavListUrlBuilder mUrlBuilder;

    public int current; // -1 for error
    public int limit; // -1 for error

    private int mFavLocalCount = 0;
    private int mFavCountSum = 0;

    private boolean mHasFirstRefresh;
    private boolean mSearchMode;
    // Avoid unnecessary search bar update
    private String mOldFavCat;
    // Avoid unnecessary search bar update
    private String mOldKeyword;
    // For modify action
    private boolean mEnableModify;
    // For modify action
    private int mModifyFavCat;
    // For modify action
    private final List<GalleryInfo> mModifyGiList = new ArrayList<>();
    // For modify action
    private boolean mModifyAdd;

    private ShowcaseView mShowcaseView;

    @Nullable
    private AddDeleteDrawable mActionFabDrawable;


    private ServiceConnection mConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            CheckUpdateService.CheckUpdateBinder binder = (CheckUpdateService.CheckUpdateBinder) service;
            binder.setListener(FavoritesScene.this);
        }

        public void onServiceDisconnected(ComponentName className) {

        }

    };

    @Override
    public int getNavCheckedItem() {
        return R.id.nav_favourite;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context context = getContext2();
        AssertUtils.assertNotNull(context);
        mClient = EhApplication.getEhClient(context);
        mFavCatArray = Settings.getFavCat();
        mFavCountArray = Settings.getFavCount();
        mFavLocalCount = Settings.getFavLocalCount();
        mFavCountSum = Settings.getFavCloudCount();

        if (savedInstanceState == null) {
            onInit();
        } else {
            onRestore(savedInstanceState);
        }
    }

    private void onInit() {
        mUrlBuilder = new FavListUrlBuilder();
        mUrlBuilder.setFavCat(Settings.getRecentFavCat());
        mSearchMode = false;
    }

    private void onRestore(Bundle savedInstanceState) {
        mUrlBuilder = savedInstanceState.getParcelable(KEY_URL_BUILDER);
        if (mUrlBuilder == null) {
            mUrlBuilder = new FavListUrlBuilder();
        }
        mSearchMode = savedInstanceState.getBoolean(KEY_SEARCH_MODE);
        mHasFirstRefresh = savedInstanceState.getBoolean(KEY_HAS_FIRST_REFRESH);
        mFavCountArray = savedInstanceState.getIntArray(KEY_FAV_COUNT_ARRAY);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        boolean hasFirstRefresh;
        if (mHelper != null && 1 == mHelper.getShownViewIndex()) {
            hasFirstRefresh = false;
        } else {
            hasFirstRefresh = mHasFirstRefresh;
        }
        outState.putBoolean(KEY_HAS_FIRST_REFRESH, hasFirstRefresh);
        outState.putParcelable(KEY_URL_BUILDER, mUrlBuilder);
        outState.putBoolean(KEY_SEARCH_MODE, mSearchMode);
        outState.putIntArray(KEY_FAV_COUNT_ARRAY, mFavCountArray);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        mClient = null;
        mFavCatArray = null;
        mFavCountArray = null;
        mFavCountSum = 0;
        mUrlBuilder = null;
        getContext2().unbindService(mConnection);

    }

    @Nullable
    @Override
    public View onCreateView2(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_favorites, container, false);
        ContentLayout contentLayout = (ContentLayout) view.findViewById(R.id.content_layout);
        MainActivity activity = getActivity2();
        AssertUtils.assertNotNull(activity);
        mDrawerLayout = (EhDrawerLayout) ViewUtils.$$(activity, R.id.draw_view);
        mRecyclerView = contentLayout.getRecyclerView();
        FastScroller fastScroller = contentLayout.getFastScroller();
        RefreshLayout refreshLayout = contentLayout.getRefreshLayout();
        mSearchBar = (SearchBar) ViewUtils.$$(view, R.id.search_bar);
        mFabLayout = (FabLayout) ViewUtils.$$(view, R.id.fab_layout);

        Context context = getContext2();
        AssertUtils.assertNotNull(context);
        Resources resources = context.getResources();
        int paddingTopSB = resources.getDimensionPixelOffset(R.dimen.gallery_padding_top_search_bar);

        mHelper = new FavoritesHelper();
        mHelper.setEmptyString(resources.getString(R.string.gallery_list_empty_hit));
        contentLayout.setHelper(mHelper);
        contentLayout.getFastScroller().setOnDragHandlerListener(this);

        mAdapter = new FavoritesAdapter(inflater, resources, mRecyclerView, Settings.getListMode());
        mRecyclerView.setSelector(Ripple.generateRippleDrawable(context, !AttrResources.getAttrBoolean(context, R.attr.isLightTheme), new ColorDrawable(Color.TRANSPARENT)));
        mRecyclerView.setDrawSelectorOnTop(true);
        mRecyclerView.setClipToPadding(false);
        mRecyclerView.setOnItemClickListener(this);
        mRecyclerView.setOnItemLongClickListener(this);
        mRecyclerView.setChoiceMode(EasyRecyclerView.CHOICE_MODE_MULTIPLE_CUSTOM);
        mRecyclerView.setCustomCheckedListener(this);

        fastScroller.setPadding(fastScroller.getPaddingLeft(), fastScroller.getPaddingTop() + paddingTopSB,
                fastScroller.getPaddingRight(), fastScroller.getPaddingBottom());

        refreshLayout.setHeaderTranslationY(paddingTopSB);
        refreshLayout.setOnRefreshListener(this);
        mLeftDrawable = new DrawerArrowDrawable(context, AttrResources.getAttrColor(context, R.attr.drawableColorPrimary));
        mSearchBar.setLeftDrawable(mLeftDrawable);
        mSearchBar.setRightDrawable(DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24));
        mSearchBar.setHelper(this);
        mSearchBar.setAllowEmptySearch(false);
        updateSearchBar();
        mSearchBarMover = new SearchBarMover(this, mSearchBar, mRecyclerView);
        mSourceBar = (RadioGroup) ViewUtils.$$(view, R.id.source_bar);
        bindSource(mSourceBar);
        mFabLayout.setAutoCancel(true);
        mFabLayout.setExpanded(false);
        mFabLayout.setHidePrimaryFab(false);
        mFabLayout.setOnClickFabListener(this);
        mFabLayout.setOnExpandListener(this);
        mActionFabDrawable = new AddDeleteDrawable(context, resources.getColor(R.color.primary_drawable_dark), currentSource);
        mFabLayout.getPrimaryFab().setImageDrawable(mActionFabDrawable);
        mHideActionFabSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mRecyclerView.addOnScrollListener(mOnScrollListener);
        addAboveSnackView(mFabLayout);

        // Restore search mode
        if (mSearchMode) {
            mSearchMode = false;
            enterSearchMode(false);
        }

        // Only refresh for the first time
        if (!mHasFirstRefresh) {
            mHasFirstRefresh = true;
            mHelper.firstRefresh();
        }

        guideCollections();

        Intent service = new Intent(context, CheckUpdateService.class);
        context.bindService(service, mConnection, Context.BIND_AUTO_CREATE);
        return view;
    }

    private void guideCollections() {
        Activity activity = getActivity2();
        if (null == activity || !Settings.getGuideCollections()) {
            return;
        }

        Display display = activity.getWindowManager().getDefaultDisplay();
        Point point = new Point();
        display.getSize(point);

        mShowcaseView = new ShowcaseView.Builder(activity)
                .withMaterialShowcase()
                .setStyle(R.style.Guide)
                .setTarget(new PointTarget(point.x, point.y / 3))
                .blockAllTouches()
                .setContentTitle(R.string.guide_collections_title)
                .setContentText(R.string.guide_collections_text)
                .replaceEndButton(R.layout.button_guide)
                .setShowcaseEventListener(new SimpleShowcaseEventListener() {
                    @Override
                    public void onShowcaseViewDidHide(ShowcaseView showcaseView) {
                        mShowcaseView = null;
                        ViewUtils.removeFromParent(showcaseView);
                        Settings.putGuideCollections(false);
                        openDrawer(Gravity.RIGHT);
                    }
                }).build();
    }

    // keyword of mUrlBuilder, fav cat of mUrlBuilder, mFavCatArray.
    // They changed, call it
    private void updateSearchBar() {
        Context context = getContext2();
        if (null == context || null == mUrlBuilder || null == mSearchBar || null == mFavCatArray) {
            return;
        }

        // Update title
        int favCat = mUrlBuilder.getFavCat();
        String favCatName;
        if (favCat >= 0 && favCat < 10) {
            favCatName = mFavCatArray[favCat];
        } else if (favCat == FavListUrlBuilder.FAV_CAT_LOCAL) {
            favCatName = getString(R.string.local_favorites) + " - " + currentSource;
        } else {
            favCatName = getString(R.string.cloud_favorites);
        }
        String keyword = mUrlBuilder.getKeyword();
        if (TextUtils.isEmpty(keyword)) {
            if (!ObjectUtils.equal(favCatName, mOldFavCat)) {
                mSearchBar.setTitle(getString(R.string.favorites_title, favCatName));
            }
        } else {
            if (!ObjectUtils.equal(favCatName, mOldFavCat) || !ObjectUtils.equal(keyword, mOldKeyword)) {
                mSearchBar.setTitle(getString(R.string.favorites_title_2, favCatName, keyword));
            }
        }

        // Update hint
        if (!ObjectUtils.equal(favCatName, mOldFavCat)) {
            Drawable searchImage = DrawableManager.getVectorDrawable(context, R.drawable.v_magnify_x24);
            SpannableStringBuilder ssb = new SpannableStringBuilder("   ");
            ssb.append(getString(R.string.favorites_search_bar_hint, favCatName));
            int textSize = (int) (mSearchBar.getEditTextTextSize() * 1.25);
            if (searchImage != null) {
                searchImage.setBounds(0, 0, textSize, textSize);
                ssb.setSpan(new ImageSpan(searchImage), 1, 2, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            mSearchBar.setEditTextHint(ssb);
        }

        mOldFavCat = favCatName;
        mOldKeyword = keyword;

        // Save recent fav cat
        Settings.putRecentFavCat(mUrlBuilder.getFavCat());
    }

    @Override
    public void update(boolean done, int current, int total) {
        Context context = getContext2();
        if (null == context || null == mUrlBuilder || null == mSearchBar || null == mFavCatArray) {
            return;
        }

        mSearchBar.setTitle(getString(R.string.favorites_checking_update, current, total));
        if (done && mHelper != null) {
            mHelper.refresh();
            mOldFavCat = "";
            updateSearchBar();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        if (null != mShowcaseView) {
            ViewUtils.removeFromParent(mShowcaseView);
            mShowcaseView = null;
        }

        if (null != mHelper) {
            mHelper.destroy();
            if (1 == mHelper.getShownViewIndex()) {
                mHasFirstRefresh = false;
            }
        }
        if (null != mRecyclerView) {
            mRecyclerView.stopScroll();
            mRecyclerView = null;
        }
        if (null != mFabLayout) {
            removeAboveSnackView(mFabLayout);
            mFabLayout = null;
        }

        mAdapter = null;

        mSearchBar = null;

        mSearchBarMover = null;
        mLeftDrawable = null;

        mOldFavCat = null;
        mOldKeyword = null;
    }

    @Override
    public void onHeaderRefresh() {
        Disposable disposable = MHApi.Companion.getINSTANCE().get(currentSource).recent(1)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    Log.d("checkupdate","info " + result.getDatas().size());

                    for (MHComicInfo info : result.getDatas()) {
                        Log.d("checkupdate","info " + info.getTitle() + " " + info.getGid() + " " + info.getPosted());

                        if (!info.getPosted().isEmpty()) {
                            Logger.d("checkupdate","info " + info.getTitle() + " " + info.getGid());
                            ReadingRecord record = EhDB.getReadingRecord(info.getGid() + "@" + currentSource);
                            if (record == null) {
                                continue;
                            }
                            Log.d("checkupdate","record " + record.getId());

                            try {
                                long timeStamp = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).parse(info.getPosted()).getTime();
                                record.setUpdate_time(timeStamp);
                                EhDB.putReadingRecord(record);
                                Log.d("checkupdate","update " + info.getTitle() + " " + info.getPosted());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    mHelper.refresh();
                });
    }

    @Override
    public void onFooterRefresh() {

    }


    private class FavDrawerHolder extends RecyclerView.ViewHolder {

        private final TextView key;
        private final TextView value;

        private FavDrawerHolder(View itemView) {
            super(itemView);
            key = (TextView) ViewUtils.$$(itemView, R.id.key);
            value = (TextView) ViewUtils.$$(itemView, R.id.value);
        }
    }

    private class FavDrawerAdapter extends RecyclerView.Adapter<FavDrawerHolder> {

        private final LayoutInflater mInflater;

        private FavDrawerAdapter(LayoutInflater inflater) {
            mInflater = inflater;
        }

        @Override
        public int getItemViewType(int position) {
            return position;
        }

        @NonNull
        @Override
        public FavDrawerHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new FavDrawerHolder(mInflater.inflate(R.layout.item_drawer_favorites, parent, false));
        }

        @Override
        @SuppressLint("SetTextI18n")
        public void onBindViewHolder(@NonNull FavDrawerHolder holder, int position) {
            if (0 == position) {
                holder.key.setText(R.string.local_favorites);
                holder.value.setText(Integer.toString(mFavLocalCount));
                holder.itemView.setEnabled(true);
            } else if (1 == position) {
                holder.key.setText(R.string.cloud_favorites);
                holder.value.setText(Integer.toString(mFavCountSum));
                holder.itemView.setEnabled(true);
            } else {
                if (null == mFavCatArray || null == mFavCountArray ||
                        mFavCatArray.length < (position - 1) ||
                        mFavCountArray.length < (position - 1)) {
                    return;
                }
                holder.key.setText(mFavCatArray[position - 2]);
                holder.value.setText(Integer.toString(mFavCountArray[position - 2]));
                holder.itemView.setEnabled(true);
            }
        }

        @Override
        public int getItemCount() {
            if (null == mFavCatArray) {
                return 2;
            }
            return 12;
        }
    }

    @Override
    public View onCreateDrawerView(LayoutInflater inflater,
                                   @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.drawer_list_rv, container, false);
        final Context context = getContext2();
        Toolbar toolbar = (Toolbar) ViewUtils.$$(view, R.id.toolbar);

        AssertUtils.assertNotNull(context);

        toolbar.setTitle(R.string.collections);
        toolbar.inflateMenu(R.menu.drawer_favorites);
        toolbar.setOnMenuItemClickListener(new Toolbar.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                switch (id) {
                    case R.id.action_default_favorites_slot:
                        String[] items = new String[12];
                        items[0] = getString(R.string.let_me_select);
                        items[1] = getString(R.string.local_favorites);
                        String[] favCat = Settings.getFavCat();
                        System.arraycopy(favCat, 0, items, 2, 10);
                        new AlertDialog.Builder(context)
                                .setTitle(R.string.default_favorites_collection)
                                .setItems(items, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        Settings.putDefaultFavSlot(which - 2);
                                    }
                                }).show();
                        return true;
                }
                return false;
            }
        });

        EasyRecyclerView recyclerView = (EasyRecyclerView) view.findViewById(R.id.recycler_view_drawer);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.addItemDecoration(new DividerItemDecoration(context, DividerItemDecoration.VERTICAL));

        mDrawerAdapter = new FavDrawerAdapter(inflater);
        recyclerView.setAdapter(mDrawerAdapter);
        recyclerView.setOnItemClickListener(this);

        return view;
    }

    @Override
    public void onDestroyDrawerView() {
        super.onDestroyDrawerView();

        mDrawerAdapter = null;
    }

    @Override
    public void onBackPressed() {
        if (null != mShowcaseView) {
            return;
        }

        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            mRecyclerView.outOfCustomChoiceMode();
        } else if (mSearchMode) {
            exitSearchMode(true);
        } else {
            finish();
        }
    }

    @Override
    @Implemented(FastScroller.OnDragHandlerListener.class)
    public void onStartDragHandler() {
        // Lock right drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    @Implemented(FastScroller.OnDragHandlerListener.class)
    public void onEndDragHandler() {
        // Restore right drawer
        if (null != mRecyclerView && !mRecyclerView.isInCustomChoice()) {
            setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
        }

        if (mSearchBarMover != null) {
            mSearchBarMover.returnSearchBarPosition();
        }
    }

    @Override
    @Implemented(EasyRecyclerView.OnItemClickListener.class)
    public boolean onItemClick(EasyRecyclerView parent, View view, int position, long id) {
        if (mDrawerLayout != null && mDrawerLayout.isDrawerOpen(Gravity.RIGHT)) {
            // Skip if in search mode
            if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
                return true;
            }

            if (mUrlBuilder == null || mHelper == null) {
                return true;
            }

            // Local favorite position is 0, All favorite position is 1, so position - 2 is OK
            int newFavCat = position - 2;

            // Check is the same
            if (mUrlBuilder.getFavCat() == newFavCat) {
                return true;
            }

            // Ensure outOfCustomChoiceMode to avoid error
            if (mRecyclerView != null) {
                mRecyclerView.isInCustomChoice();
            }

            exitSearchMode(true);

            mUrlBuilder.setKeyword(null);
            mUrlBuilder.setFavCat(newFavCat);
            updateSearchBar();
            mHelper.refresh();

            closeDrawer(Gravity.RIGHT);

        } else {
            if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
                mRecyclerView.toggleItemChecked(position);
            } else if (mHelper != null) {
                GalleryInfo gi = mHelper.getDataAtEx(position);
                if (gi == null) {
                    return true;
                }
                Bundle args = new Bundle();
                args.putString(GalleryDetailScene.KEY_ACTION, GalleryDetailScene.ACTION_GALLERY_INFO);
                args.putParcelable(GalleryDetailScene.KEY_GALLERY_INFO, gi);
                Announcer announcer = new Announcer(GalleryDetailScene.class).setArgs(args);
                View thumb;
                if (null != (thumb = view.findViewById(R.id.thumb))) {
                    announcer.setTranHelper(new EnterGalleryDetailTransaction(thumb));
                }
                startScene(announcer);
            }
        }
        return true;
    }

    @Override
    @Implemented(EasyRecyclerView.OnItemLongClickListener.class)
    public boolean onItemLongClick(EasyRecyclerView parent, View view, int position, long id) {
        // Can not into
        if (mRecyclerView != null && !mSearchMode) {
            if (!mRecyclerView.isInCustomChoice()) {
                mRecyclerView.intoCustomChoiceMode();
            }
            mRecyclerView.toggleItemChecked(position);
        }
        return true;
    }

    @Override
    @Implemented(SearchBarMover.Helper.class)
    public boolean isValidView(RecyclerView recyclerView) {
        return recyclerView == mRecyclerView;
    }

    @Override
    @Implemented(SearchBarMover.Helper.class)
    public RecyclerView getValidRecyclerView() {
        return mRecyclerView;
    }

    @Override
    @Implemented(SearchBarMover.Helper.class)
    public boolean forceShowSearchBar() {
        return false;
    }

    @Override
    @Implemented(SearchBar.Helper.class)
    public void onClickTitle() {
        // Skip if in search mode
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            return;
        }

        if (!mSearchMode) {
            enterSearchMode(true);
        }
    }

    @Override
    @Implemented(SearchBar.Helper.class)
    public void onClickLeftIcon() {
        // Skip if in search mode
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            return;
        }

        if (mSearchMode) {
            exitSearchMode(true);
        } else {
            toggleDrawer(Gravity.LEFT);
        }
    }

    @Override
    @Implemented(SearchBar.Helper.class)
    public void onClickRightIcon() {
        // Skip if in search mode
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            return;
        }

        if (!mSearchMode) {
            enterSearchMode(true);
        } else {
            if (mSearchBar != null) {
                mSearchBar.applySearch();
            }
        }
    }

    @Override
    @Implemented(SearchBar.Helper.class)
    public void onSearchEditTextClick() {
    }

    @Override
    @Implemented(SearchBar.Helper.class)
    public void onApplySearch(String query) {
        // Skip if in search mode
        if (mRecyclerView != null && mRecyclerView.isInCustomChoice()) {
            return;
        }

        if (mUrlBuilder == null || mHelper == null) {
            return;
        }

        // Ensure outOfCustomChoiceMode to avoid error
        if (mRecyclerView != null) {
            mRecyclerView.isInCustomChoice();
        }

        exitSearchMode(true);

        mUrlBuilder.setKeyword(query);
        updateSearchBar();
        mHelper.refresh();
    }

    @Override
    @Implemented(SearchBar.Helper.class)
    public void onSearchEditTextBackPressed() {
        onBackPressed();
    }

    @Override
    @Implemented(FabLayout.OnClickFabListener.class)
    public void onClickPrimaryFab(FabLayout view, FloatingActionButton fab) {
        view.toggle();
    }

    @Override
    @Implemented(FabLayout.OnExpandListener.class)
    public void onExpand(boolean expanded) {
        if (null == mActionFabDrawable) {
            return;
        }

        if (expanded) {
            setDrawerLockMode(com.hippo.drawerlayout.DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
            setDrawerLockMode(com.hippo.drawerlayout.DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
            mActionFabDrawable.setDelete(ANIMATE_TIME);
        } else {
            setDrawerLockMode(com.hippo.drawerlayout.DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
            setDrawerLockMode(com.hippo.drawerlayout.DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
            mActionFabDrawable.setAdd(ANIMATE_TIME, currentSource);
        }
    }


    @Override
    @Implemented(FabLayout.OnClickFabListener.class)
    public void onClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {
        Context context = getContext2();
        if (null == context || null == mRecyclerView || null == mHelper) {
            return;
        }

        if (fab.getTag() instanceof String) {
            String source = (String) fab.getTag();
            switchSource(source);
            mHelper.refresh();
        } else {
            // Intent intent = new Intent(getActivity2(), CheckUpdateService.class);
            // intent.setAction(CheckUpdateService.ACTION_START);
            // context.bindService()
            // context.startService(intent);
            // showTip(R.string.check_update_start, BaseScene.LENGTH_SHORT);

            clearUpdate();
        }
        view.setExpanded(false);
        updateSearchBar();
    }

    @Override
    @Implemented(FabLayout.OnClickFabListener.class)
    public void onLongClickSecondaryFab(FabLayout view, FloatingActionButton fab, int position) {

    }


    @Override
    @Implemented(EasyRecyclerView.CustomChoiceListener.class)
    public void onIntoCustomChoice(EasyRecyclerView view) {
        if (mFabLayout != null) {
            mFabLayout.setExpanded(true);
        }
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(false);
        }
        // Lock drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.LEFT);
        setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, Gravity.RIGHT);
    }

    @Override
    @Implemented(EasyRecyclerView.CustomChoiceListener.class)
    public void onOutOfCustomChoice(EasyRecyclerView view) {
        if (mFabLayout != null) {
            mFabLayout.setExpanded(false);
        }
        if (mHelper != null) {
            mHelper.setRefreshLayoutEnable(true);
        }
        // Unlock drawer
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.LEFT);
        setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, Gravity.RIGHT);
    }

    @Override
    @Implemented(EasyRecyclerView.CustomChoiceListener.class)
    public void onItemCheckedStateChanged(EasyRecyclerView view, int position, long id, boolean checked) {
        if (view.getCheckedItemCount() == 0) {
            view.outOfCustomChoiceMode();
        }
    }

    private void enterSearchMode(boolean animation) {
        if (mSearchMode || mSearchBar == null || mSearchBarMover == null || mLeftDrawable == null) {
            return;
        }
        mSearchMode = true;
        mSearchBar.setState(SearchBar.STATE_SEARCH_LIST, animation);
        mSearchBarMover.returnSearchBarPosition(animation);
        mLeftDrawable.setArrow(ANIMATE_TIME);
    }

    private void exitSearchMode(boolean animation) {
        if (!mSearchMode || mSearchBar == null || mSearchBarMover == null || mLeftDrawable == null) {
            return;
        }
        mSearchMode = false;
        mSearchBar.setState(SearchBar.STATE_NORMAL, animation);
        mSearchBarMover.returnSearchBarPosition();
        mLeftDrawable.setMenu(ANIMATE_TIME);
    }

    private void onGetFavoritesSuccess(FavoritesParser.Result result, int taskId) {
        if (mHelper != null && mSearchBarMover != null &&
                mHelper.isCurrentTask(taskId)) {

            if (mFavCatArray != null) {
                System.arraycopy(result.catArray, 0, mFavCatArray, 0, 10);
            }

            mFavCountArray = result.countArray;
            if (mFavCountArray != null) {
                mFavCountSum = 0;
                for (int i = 0; i < 10; i++) {
                    mFavCountSum = mFavCountSum + mFavCountArray[i];
                }
                Settings.putFavCloudCount(mFavCountSum);
            }

            updateSearchBar();
            mHelper.onGetPageData(taskId, result.pages, result.nextPage, result.galleryInfoList);

            if (mDrawerAdapter != null) {
                mDrawerAdapter.notifyDataSetChanged();
            }
        }
    }

    private void onGetFavoritesFailure(Exception e, int taskId) {
        if (mHelper != null && mSearchBarMover != null &&
                mHelper.isCurrentTask(taskId)) {
            mHelper.onGetException(taskId, e);
        }
    }

    @SuppressWarnings("unchecked")
    private void onGetFavoritesLocal(String keyword, int taskId) {
        if (mHelper != null && mHelper.isCurrentTask(taskId)) {
            List<GalleryInfo> list;
            if (TextUtils.isEmpty(keyword)) {
                list = EhDB.getLocalFavorites(currentSource);
            } else {
                list = EhDB.searchLocalFavorites(keyword);
            }

            checkUpdate(list);
            if (list.size() == 0) {
                mHelper.onGetPageData(taskId, 0, 0, Collections.EMPTY_LIST);
            } else {
                mHelper.onGetPageData(taskId, 1, 0, list);
            }

            if (TextUtils.isEmpty(keyword)) {
                mFavLocalCount = list.size();
                Settings.putFavLocalCount(mFavLocalCount);
                if (mDrawerAdapter != null) {
                    mDrawerAdapter.notifyDataSetChanged();
                }
            }
        }
    }

    private void checkUpdate(List<GalleryInfo> list) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA);
        for (GalleryInfo info : list) {
            ReadingRecord record = EhDB.getReadingRecord(info.getId());
            if (record == null) {
                info.posted = sdf.format(new Date(0L));
                continue;
            }
            info.posted = sdf.format(new Date(record.getUpdate_time()));

            if (record.getRead_time() == null || record.getRead_time() < record.getUpdate_time()) {
                info.category = EhConfig.UPDATE;
            }
        }

        Collections.sort(list, (o1, o2) -> {
            try {
                long ret = sdf.parse(o2.posted).getTime() - sdf.parse(o1.posted).getTime();
                if (ret > 0) return 1;
                else if (ret < 0) return -1;
                else return 0;
            } catch (Exception e) {
                return 0;
            }
        });
    }

    private class DeleteDialogHelper implements DialogInterface.OnClickListener,
            DialogInterface.OnCancelListener {

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (which != DialogInterface.BUTTON_POSITIVE) {
                return;
            }
            if (mRecyclerView == null || mHelper == null || mUrlBuilder == null) {
                return;
            }

            mRecyclerView.outOfCustomChoiceMode();

            if (mUrlBuilder.getFavCat() == FavListUrlBuilder.FAV_CAT_LOCAL) { // Delete local fav
                EhDB.removeLocalFavorites(mModifyGiList.toArray(new GalleryInfo[0]));
                mModifyGiList.clear();
                mHelper.refresh();
            } else { // Delete cloud fav
                mEnableModify = true;
                mModifyFavCat = -1;
                mModifyAdd = false;
                mHelper.refresh();
            }
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            mModifyGiList.clear();
        }
    }

    private class MoveDialogHelper implements DialogInterface.OnClickListener,
            DialogInterface.OnCancelListener {

        @Override
        public void onClick(DialogInterface dialog, int which) {
            if (mRecyclerView == null || mHelper == null || mUrlBuilder == null) {
                return;
            }
            int srcCat = mUrlBuilder.getFavCat();
            int dstCat;
            if (which == 0) {
                dstCat = FavListUrlBuilder.FAV_CAT_LOCAL;
            } else {
                dstCat = which - 1;
            }
            if (srcCat == dstCat) {
                return;
            }

            mRecyclerView.outOfCustomChoiceMode();

            if (srcCat == FavListUrlBuilder.FAV_CAT_LOCAL) { // Move from local to cloud
                EhDB.removeLocalFavorites(mModifyGiList.toArray(new GalleryInfo[0]));
                mEnableModify = true;
                mModifyFavCat = dstCat;
                mModifyAdd = true;
                mHelper.refresh();
            } else if (dstCat == FavListUrlBuilder.FAV_CAT_LOCAL) { // Move from cloud to local
                EhDB.putLocalFavorites(mModifyGiList);
                mEnableModify = true;
                mModifyFavCat = -1;
                mModifyAdd = false;
                mHelper.refresh();
            } else {
                mEnableModify = true;
                mModifyFavCat = dstCat;
                mModifyAdd = false;
                mHelper.refresh();
            }
        }

        @Override
        public void onCancel(DialogInterface dialog) {
            mModifyGiList.clear();
        }
    }

    private class FavoritesAdapter extends GalleryAdapter {

        public FavoritesAdapter(@NonNull LayoutInflater inflater, @NonNull Resources resources,
                                @NonNull RecyclerView recyclerView, int type) {
            super(inflater, resources, recyclerView, type, false);
        }

        @Override
        public int getItemCount() {
            return null != mHelper ? mHelper.size() : 0;
        }

        @Nullable
        @Override
        public GalleryInfo getDataAt(int position) {
            return null != mHelper ? mHelper.getDataAtEx(position) : null;
        }
    }

    private class FavoritesHelper extends GalleryInfoContentHelper {

        @Override
        protected void getPageData(final int taskId, int type, int page) {
            MainActivity activity = getActivity2();
            if (null == activity || null == mUrlBuilder || null == mClient) {
                return;
            }

            if (mEnableModify) {
                mEnableModify = false;

                boolean local = mUrlBuilder.getFavCat() == FavListUrlBuilder.FAV_CAT_LOCAL;

                if (mModifyAdd) {
                    String[] gidArray = new String[mModifyGiList.size()];
                    String[] tokenArray = new String[mModifyGiList.size()];
                    for (int i = 0, n = mModifyGiList.size(); i < n; i++) {
                        GalleryInfo gi = mModifyGiList.get(i);
                        gidArray[i] = gi.gid;
                        tokenArray[i] = gi.token;
                    }
                    List<GalleryInfo> modifyGiListBackup = new ArrayList<>(mModifyGiList);
                    mModifyGiList.clear();

                    EhRequest request = new EhRequest();
                    request.setMethod(EhClient.METHOD_ADD_FAVORITES_RANGE);
                    request.setCallback(new AddFavoritesListener(getContext(),
                            activity.getStageId(), getTag(),
                            taskId, mUrlBuilder.getKeyword(), modifyGiListBackup));
                    request.setArgs(gidArray, tokenArray, mModifyFavCat);
                    mClient.execute(request);
                } else {
                    String[] gidArray = new String[mModifyGiList.size()];
                    for (int i = 0, n = mModifyGiList.size(); i < n; i++) {
                        gidArray[i] = mModifyGiList.get(i).gid;
                    }
                    mModifyGiList.clear();

                    String url;
                    if (local) {
                        // Local fav is shown now, but operation need be done for cloud fav
                        url = EhUrl.getFavoritesUrl();
                    } else {
                        url = mUrlBuilder.build();
                    }

                    mUrlBuilder.setIndex(page);
                    EhRequest request = new EhRequest();
                    request.setMethod(EhClient.METHOD_MODIFY_FAVORITES);
                    request.setCallback(new GetFavoritesListener(getContext(),
                            activity.getStageId(), getTag(),
                            taskId, local, mUrlBuilder.getKeyword()));
                    request.setArgs(url, gidArray, mModifyFavCat, Settings.getShowJpnTitle());
                    mClient.execute(request);
                }
            } else if (mUrlBuilder.getFavCat() == FavListUrlBuilder.FAV_CAT_LOCAL) {
                final String keyword = mUrlBuilder.getKeyword();
                SimpleHandler.getInstance().post(new Runnable() {
                    @Override
                    public void run() {
                        onGetFavoritesLocal(keyword, taskId);
                    }
                });
            } else {
                EhRequest request = new EhRequest();
                request.setMethod(EhClient.METHOD_GET_FAVORITES);
                request.setCallback(new GetFavoritesListener(getContext(),
                        activity.getStageId(), getTag(),
                        taskId, false, mUrlBuilder.getKeyword()));
                request.setArgs(page);
                mClient.execute(request);
            }
        }

        @Override
        protected Context getContext() {
            return FavoritesScene.this.getContext2();
        }

        @Override
        protected void notifyDataSetChanged() {
            // Ensure outOfCustomChoiceMode to avoid error
            if (mRecyclerView != null) {
                mRecyclerView.outOfCustomChoiceMode();
            }

            if (mAdapter != null) {
                mAdapter.notifyDataSetChanged();
            }
        }

        @Override
        protected void notifyItemRangeRemoved(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeRemoved(positionStart, itemCount);
            }
        }

        @Override
        protected void notifyItemRangeInserted(int positionStart, int itemCount) {
            if (mAdapter != null) {
                mAdapter.notifyItemRangeInserted(positionStart, itemCount);
            }
        }

        @Override
        public void onShowView(View hiddenView, View shownView) {
            if (null != mSearchBarMover) {
                mSearchBarMover.showSearchBar();
            }
        }

        @Override
        protected boolean isDuplicate(GalleryInfo d1, GalleryInfo d2) {
            return d1.gid == d2.gid;
        }

        @Override
        protected void onScrollToPosition(int postion) {
            if (0 == postion) {
                if (null != mSearchBarMover) {
                    mSearchBarMover.showSearchBar();
                }
            }
        }
    }

    private static class AddFavoritesListener extends EhCallback<FavoritesScene, Void> {

        private final int mTaskId;
        private final String mKeyword;
        private final List<GalleryInfo> mBackup;

        private AddFavoritesListener(Context context, int stageId,
                                     String sceneTag, int taskId, String keyword, List<GalleryInfo> backup) {
            super(context, stageId, sceneTag);
            mTaskId = taskId;
            mKeyword = keyword;
            mBackup = backup;
        }

        @Override
        public void onSuccess(Void result) {
            FavoritesScene scene = getScene();
            if (scene != null) {
                scene.onGetFavoritesLocal(mKeyword, mTaskId);
            }
        }

        @Override
        public void onFailure(Exception e) {
            // TODO It's a failure, add all of backup back to db.
            // But how to known which one is failed?
            EhDB.putLocalFavorites(mBackup);

            FavoritesScene scene = getScene();
            if (scene != null) {
                scene.onGetFavoritesLocal(mKeyword, mTaskId);
            }
        }

        @Override
        public void onCancel() {
        }

        @Override
        public boolean isInstance(SceneFragment scene) {
            return scene instanceof FavoritesScene;
        }
    }

    private static class GetFavoritesListener extends EhCallback<FavoritesScene, FavoritesParser.Result> {

        private final int mTaskId;
        // Local fav is shown now, but operation need be done for cloud fav
        private final boolean mLocal;
        private final String mKeyword;

        private GetFavoritesListener(Context context, int stageId,
                                     String sceneTag, int taskId, boolean local, String keyword) {
            super(context, stageId, sceneTag);
            mTaskId = taskId;
            mLocal = local;
            mKeyword = keyword;
        }

        @Override
        public void onSuccess(FavoritesParser.Result result) {
            // Put fav cat
            Settings.putFavCat(result.catArray);
            Settings.putFavCount(result.countArray);
            FavoritesScene scene = getScene();
            if (scene != null) {
                if (mLocal) {
                    scene.onGetFavoritesLocal(mKeyword, mTaskId);
                } else {
                    scene.onGetFavoritesSuccess(result, mTaskId);
                }
            }
        }

        @Override
        public void onFailure(Exception e) {
            FavoritesScene scene = getScene();
            if (scene != null) {
                if (mLocal) {
                    e.printStackTrace();
                    scene.onGetFavoritesLocal(mKeyword, mTaskId);
                } else {
                    scene.onGetFavoritesFailure(e, mTaskId);
                }
            }
        }

        @Override
        public void onCancel() {
        }

        @Override
        public boolean isInstance(SceneFragment scene) {
            return scene instanceof FavoritesScene;
        }
    }

    private int mHideActionFabSlop;
    private boolean mShowActionFab = true;

    @Nullable
    private final RecyclerView.OnScrollListener mOnScrollListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int newState) {
        }

        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (dy >= mHideActionFabSlop) {
                hideActionFab();
            } else if (dy <= -mHideActionFabSlop / 2) {
                showActionFab();
            }
        }
    };

    private void showActionFab() {
        if (null != mFabLayout && !mShowActionFab) {
            mShowActionFab = true;
            View fab = mFabLayout.getPrimaryFab();
            fab.setVisibility(View.VISIBLE);
            fab.setRotation(-45.0f);
            fab.animate().scaleX(1.0f).scaleY(1.0f).rotation(0.0f).setListener(null)
                    .setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.FAST_SLOW_INTERPOLATOR).start();
        }
    }

    private void hideActionFab() {
        if (null != mFabLayout && mShowActionFab) {
            mShowActionFab = false;
            View fab = mFabLayout.getPrimaryFab();
            fab.animate().scaleX(0.0f).scaleY(0.0f).setListener(mActionFabAnimatorListener)
                    .setDuration(ANIMATE_TIME).setStartDelay(0L)
                    .setInterpolator(AnimationUtils.SLOW_FAST_INTERPOLATOR).start();
        }
    }

    @Nullable
    private final Animator.AnimatorListener mActionFabAnimatorListener = new SimpleAnimatorListener() {
        @Override
        public void onAnimationEnd(Animator animation) {
            if (null != mFabLayout) {
                ((View) mFabLayout.getPrimaryFab()).setVisibility(View.INVISIBLE);
            }
        }
    };

    private void bindSource(ViewGroup parent) {
        LayoutInflater inflater = getLayoutInflater2();
        if (mFabLayout == null) {
            return;
        }

        if (inflater == null) {
            return;
        }

        for (MHPlugin source : MHPluginManager.Companion.getINSTANCE().livePlugin()) {
            RadioButton item = (RadioButton) inflater.inflate(R.layout.item_source_bar, parent, false);
            item.setText(source.getName().substring(0, 2));
            item.setTag(source.getName());
            item.setId(View.generateViewId());
            parent.addView(item);
            if (source.getName().equals(currentSource)) {
                item.setChecked(true);
            }
            item.setOnClickListener(v -> {
                switchSource((String) v.getTag());
                mHelper.refresh();
                updateSearchBar();
            });

            item.setOnLongClickListener(v -> {
                String target = (String) v.getTag();
                new AlertDialog.Builder(getContext2())
                        .setTitle(getContext2().getResources().getString(R.string.import_collection, target, currentSource))
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            Intent intent = new Intent(getActivity2(), ImportService.class);
                            intent.setAction(ImportService.Companion.getACTION_START());
                            intent.putExtra(ImportService.Companion.getKEY_TARGET(), target);
                            intent.putExtra(ImportService.Companion.getKEY_SOURCE(), currentSource);
                            intent.putExtra(ImportService.Companion.getKEY_LOCAL(), mUrlBuilder.getFavCat() == FavListUrlBuilder.FAV_CAT_LOCAL);
                            getContext2().startService(intent);
                        }).create().show();
                return true;
            });
        }
    }

    private void clearUpdate() {
        List<GalleryInfo> infos = EhDB.getLocalFavorites(currentSource);
        for(GalleryInfo info : infos) {
            ReadingRecord record = EhDB.getReadingRecord(info.getId());
            if (record == null) {
                record = new ReadingRecord();
            }

            record.setId(info.getId());
            record.setRead_time(System.currentTimeMillis());
            EhDB.putReadingRecord(record);
        }
        mHelper.refresh();
    }
}

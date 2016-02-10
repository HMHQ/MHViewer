/*
 * Copyright (C) 2015 Hippo Seven
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

package com.hippo.gl.glrenderer;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Process;
import android.os.SystemClock;
import android.support.annotation.IntDef;
import android.support.annotation.NonNull;

import com.hippo.gl.image.AnimatedImage;
import com.hippo.gl.image.Image;
import com.hippo.gl.view.GLRoot;
import com.hippo.yorozuya.InfiniteThreadExecutor;
import com.hippo.yorozuya.PVLock;
import com.hippo.yorozuya.PriorityThreadFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public class ImageTexture implements Texture {

    @IntDef({SMALL, LARGE})
    @Retention(RetentionPolicy.SOURCE)
    private @interface Size {}

    private static final int SMALL = 0;
    private static final int LARGE = 1;

    private static final int SMALL_CONTENT_SIZE = 254;
    private static final int SMALL_BORDER_SIZE = 1;
    private static final int SMALL_TILE_SIZE = SMALL_CONTENT_SIZE + 2 * SMALL_BORDER_SIZE;

    private static final int LARGE_CONTENT_SIZE = SMALL_CONTENT_SIZE * 2;
    private static final int LARGE_BORDER_SIZE = SMALL_BORDER_SIZE * 2;
    private static final int LARGE_TILE_SIZE = LARGE_CONTENT_SIZE + 2 * LARGE_BORDER_SIZE;

    private static final int INIT_CAPACITY = 8;

    // We are targeting at 60fps, so we have 16ms for each frame.
    // In this 16ms, we use about 4~8 ms to upload tiles.
    private static final long UPLOAD_TILE_LIMIT = 4; // ms

    private static Bitmap sSmallUploadBitmap;
    private static Bitmap sLargeUploadBitmap;

    private static InfiniteThreadExecutor sThreadExecutor;
    private static PVLock sPVLock;

    private static Tile sSmallFreeTileHead = null;
    private static Tile sLargeFreeTileHead = null;
    private static final Object sFreeTileLock = new Object();

    public static void prepareResources() {
        sSmallUploadBitmap = Bitmap.createBitmap(SMALL_TILE_SIZE, SMALL_TILE_SIZE, Bitmap.Config.ARGB_8888);
        sLargeUploadBitmap = Bitmap.createBitmap(LARGE_TILE_SIZE, LARGE_TILE_SIZE, Bitmap.Config.ARGB_8888);
        sThreadExecutor = new InfiniteThreadExecutor(3000, new LinkedBlockingQueue<Runnable>(),
                new PriorityThreadFactory(ImageTexture.class.getSimpleName(), Process.THREAD_PRIORITY_BACKGROUND));
        sPVLock = new PVLock(3);
    }

    public static void freeResources() {
        sSmallUploadBitmap = null;
        sLargeUploadBitmap = null;
        sThreadExecutor = null;
        sPVLock = null;
    }

    private final Image mImage;
    private int mUploadIndex = 0;
    private final Tile[] mTiles;  // Can be modified in different threads.
                                  // Should be protected by "synchronized."

    private final int mWidth;
    private final int mHeight;
    private final RectF mSrcRect = new RectF();
    private final RectF mDestRect = new RectF();

    private boolean mRecycled = false;
    private boolean mRecycleLock = false;
    private boolean mPause = false;
    private volatile boolean mConfirmFrame = false;
    private int mTargetFrame = -1;
    private Callback mCallback;

    public static class Uploader implements GLRoot.OnGLIdleListener {
        private final ArrayDeque<ImageTexture> mTextures =
                new ArrayDeque<>(INIT_CAPACITY);

        private final GLRoot mGlRoot;
        private boolean mIsQueued = false;

        public Uploader(GLRoot glRoot) {
            mGlRoot = glRoot;
        }

        public synchronized void clear() {
            mTextures.clear();
        }

        public synchronized void addTexture(ImageTexture t) {
            if (t.isReady()) return;
            mTextures.addLast(t);

            if (mIsQueued) return;
            mIsQueued = true;
            mGlRoot.addOnGLIdleListener(this);
        }

        @Override
        public boolean onGLIdle(GLCanvas canvas, boolean renderRequested) {
            ArrayDeque<ImageTexture> deque = mTextures;
            synchronized (this) {
                long now = SystemClock.uptimeMillis();
                long dueTime = now + UPLOAD_TILE_LIMIT;
                while (now < dueTime && !deque.isEmpty()) {
                    ImageTexture t = deque.peekFirst();
                    if (t.uploadNextTile(canvas)) {
                        deque.removeFirst();
                        mGlRoot.requestRender();
                    }
                    now = SystemClock.uptimeMillis();
                }
                mIsQueued = !mTextures.isEmpty();

                // return true to keep this listener in the queue
                return mIsQueued;
            }
        }
    }

    private static class Tile extends UploadedTexture {

        private int mSize;
        public int offsetX;
        public int offsetY;
        public Image image;
        public Tile nextFreeTile;
        public int contentWidth;
        public int contentHeight;
        public int borderSize;
        public int tileSize;
        public Bitmap uploadBitmap;

        public void setSize(@Size int size, int width, int height) {
            mSize = size;
            if (size == SMALL) {
                borderSize = SMALL_BORDER_SIZE;
                tileSize = SMALL_TILE_SIZE;
                uploadBitmap = sSmallUploadBitmap;
            } else if (size == LARGE) {
                borderSize = LARGE_BORDER_SIZE;
                tileSize = LARGE_TILE_SIZE;
                uploadBitmap = sLargeUploadBitmap;
            } else {
                throw new IllegalStateException("Not support size " + size);
            }
            contentWidth = width;
            contentHeight = height;
            mWidth = width + 2 * borderSize;
            mHeight = height + 2 * borderSize;
            mTextureWidth = tileSize;
            mTextureHeight = tileSize;
        }

        @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
        @Override
        protected Bitmap onGetBitmap() {
            final Image image = this.image;
            if (image != null) {
                synchronized (image) {
                    image.copyPixels(offsetX - borderSize, offsetY - borderSize, uploadBitmap,
                            0, 0, tileSize, tileSize, Color.TRANSPARENT);
                }
            }
            return uploadBitmap;
        }

        @Override
        protected void onFreeBitmap(Bitmap bitmap) {
            // Nothing
        }

        @Override
        public boolean isOpaque() {
            return false;
        }

        private void invalidateInternal() {
            invalidateContent();
            image = null;
            uploadBitmap = null;
        }

        @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
        public void invalidate() {
            final Image image = this.image;
            if (image != null) {
                synchronized (image) {
                    invalidateInternal();
                }
            } else {
                invalidateInternal();
            }
        }

        public void free() {
            switch (mSize) {
                case SMALL:
                    freeSmallTile(this);
                    break;
                case LARGE:
                    freeLargeTile(this);
                    break;
                default:
                    throw new IllegalStateException("Not support size " + mSize);
            }
        }
    }

    private static Tile obtainSmallTile() {
        synchronized (sFreeTileLock) {
            Tile result = sSmallFreeTileHead;
            if (result == null) {
                return new Tile();
            } else {
                sSmallFreeTileHead = result.nextFreeTile;
                result.nextFreeTile = null;
            }
            return result;
        }
    }

    private static Tile obtainLargeTile() {
        synchronized (sFreeTileLock) {
            Tile result = sLargeFreeTileHead;
            if (result == null) {
                return new Tile();
            } else {
                sLargeFreeTileHead = result.nextFreeTile;
                result.nextFreeTile = null;
            }
            return result;
        }
    }

    private static void freeSmallTile(Tile tile) {
        tile.invalidate();
        synchronized (sFreeTileLock) {
            tile.nextFreeTile = sSmallFreeTileHead;
            sSmallFreeTileHead = tile;
        }
    }

    private static void freeLargeTile(Tile tile) {
        tile.invalidate();
        synchronized (sFreeTileLock) {
            tile.nextFreeTile = sLargeFreeTileHead;
            sLargeFreeTileHead = tile;
        }
    }

    private boolean uploadNextTile(GLCanvas canvas) {
        if (mUploadIndex == mTiles.length) return true;

        synchronized (mTiles) {
            Tile next = mTiles[mUploadIndex++];

            // Make sure tile has not already been recycled by the time
            // this is called (race condition in onGLIdle)
            if (next.image != null) {
                boolean hasBeenLoad = next.isLoaded();
                next.updateContent(canvas);

                // It will take some time for a texture to be drawn for the first
                // time. When scrolling, we need to draw several tiles on the screen
                // at the same time. It may cause a UI jank even these textures has
                // been uploaded.
                if (!hasBeenLoad) next.draw(canvas, 0, 0);
            }
        }
        return mUploadIndex == mTiles.length;
    }

    private class DecodeTask implements Runnable {

        private boolean checkRecycle() {
            if (mRecycled) {
                if (!mImage.isRecycled()) {
                    mImage.recycle();
                }
                return true;
            } else {
                return false;
            }
        }

        @Override
        public void run() {
            synchronized (mImage) {
                if (checkRecycle()) {
                    return;
                }
                mRecycleLock = true;
            }

            AnimatedImage aImage = (AnimatedImage) mImage;

            // Avoid a lot of decode thread at same time
            sPVLock.p();
            boolean decode = aImage.decode();
            sPVLock.v();

            synchronized (mImage) {
                mRecycleLock = false;
                if (checkRecycle()) {
                    return;
                }
            }

            // No need to do animate if decode failed
            if (!decode) {
                return;
            }

            long delay;
            long time;
            int frame = aImage.getCurrentFrame();
            int frameCount = aImage.getFrameCount();
            long forecast = System.currentTimeMillis();

            // No need to do animate if only on frame
            if (frameCount <= 1) {
                return;
            }

            while (true) {
                synchronized (mImage) {
                    // Check recycle and unlock recycle
                    mRecycleLock = false;
                    if (checkRecycle()) {
                        return;
                    }

                    // Check pause
                    while (mPause && mTargetFrame == -1) {
                        try {
                            mImage.wait();
                        } catch (InterruptedException e) {
                            // Ignore
                        }
                        mPause = false;

                        // Check recycle
                        if (checkRecycle()) {
                            return;
                        }

                        // Update forecast
                        forecast = System.currentTimeMillis();
                    }

                    // Get next frame and delay
                    time = System.currentTimeMillis();
                    if (mTargetFrame == -1) {
                        frame = (frame + 1) % frameCount;
                        delay = aImage.getDelay(frame) - (time - forecast);
                    } else {
                        frame = mTargetFrame;
                        delay = 0;
                        mTargetFrame = -1;
                    }
                    forecast = time + delay;
                }

                // Do sleep
                if (delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }

                // Check recycle and lock recycle
                synchronized (mImage) {
                    if (checkRecycle()) {
                        return;
                    }
                    mRecycleLock = true;
                }

                aImage.setCurrentFrame(frame);
                mConfirmFrame = true;

                // Notify
                Callback callback = mCallback;
                if (callback != null) {
                    callback.invalidateImage(mImage);
                }
            }
        }
    }

    public ImageTexture(@NonNull Image image) {
        mImage = image;
        int width = mWidth = image.getWidth();
        int height = mHeight = image.getHeight();
        ArrayList<Tile> list = new ArrayList<>();

        for (int x = 0; x < width; x += LARGE_CONTENT_SIZE) {
            for (int y = 0; y < height; y += LARGE_CONTENT_SIZE) {
                int w = Math.min(LARGE_CONTENT_SIZE, width - x);
                int h = Math.min(LARGE_CONTENT_SIZE, height - y);

                if (w <= SMALL_TILE_SIZE) {
                    Tile tile = obtainSmallTile();
                    tile.offsetX = x;
                    tile.offsetY = y;
                    tile.image = image;
                    tile.setSize(SMALL, w, Math.min(SMALL_TILE_SIZE, h));
                    list.add(tile);

                    int nextHeight = h - SMALL_TILE_SIZE;
                    if (nextHeight > 0) {
                        Tile nextTile = obtainSmallTile();
                        nextTile.offsetX = x;
                        nextTile.offsetY = y + SMALL_TILE_SIZE;
                        nextTile.image = image;
                        nextTile.setSize(SMALL, w, nextHeight);
                        list.add(nextTile);
                    }

                } else if (h <= SMALL_TILE_SIZE) {
                    Tile tile = obtainSmallTile();
                    tile.offsetX = x;
                    tile.offsetY = y;
                    tile.image = image;
                    tile.setSize(SMALL, Math.min(SMALL_TILE_SIZE, w), h);
                    list.add(tile);

                    int nextWidth = w - SMALL_TILE_SIZE;
                    if (nextWidth > 0) {
                        Tile nextTile = obtainSmallTile();
                        nextTile.offsetX = x + SMALL_TILE_SIZE;
                        nextTile.offsetY = y;
                        nextTile.image = image;
                        nextTile.setSize(SMALL, nextWidth, h);
                        list.add(nextTile);
                    }

                } else {
                    Tile tile = obtainLargeTile();
                    tile.offsetX = x;
                    tile.offsetY = y;
                    tile.image = image;
                    tile.setSize(LARGE, w, h);
                    list.add(tile);
                }
            }
        }

        mTiles = list.toArray(new Tile[list.size()]);

        if (image instanceof AnimatedImage) {
            sThreadExecutor.execute(new DecodeTask());
        }
    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    @Override
    public int getWidth() {
        return mWidth;
    }

    @Override
    public int getHeight() {
        return mHeight;
    }

    // We want to draw the "source" on the "target".
    // This method is to find the "output" rectangle which is
    // the corresponding area of the "src".
    //                                   (x,y)  target
    // (x0,y0)  source                     +---------------+
    //    +----------+                     |               |
    //    | src      |                     | output        |
    //    | +--+     |    linear map       | +----+        |
    //    | +--+     |    ---------->      | |    |        |
    //    |          | by (scaleX, scaleY) | +----+        |
    //    +----------+                     |               |
    //      Texture                        +---------------+
    //                                          Canvas
    private static void mapRect(RectF output,
            RectF src, float x0, float y0, float x, float y, float scaleX,
            float scaleY) {
        output.set(x + (src.left - x0) * scaleX,
                y + (src.top - y0) * scaleY,
                x + (src.right - x0) * scaleX,
                y + (src.bottom - y0) * scaleY);
    }

    private void syncFrame() {
        if (mConfirmFrame && mImage instanceof AnimatedImage) {
            mConfirmFrame = false;

            // invalid tiles
            for (int i = 0, n = mTiles.length; i < n; i++) {
                Tile tile = mTiles[i];
                int width = tile.mWidth;
                int height = tile.mHeight;
                tile.invalidateContent();
                tile.mWidth = width;
                tile.mHeight = height;
            }
        }
    }

    @Override
    public void draw(GLCanvas canvas, int x, int y) {
        draw(canvas, x, y, mWidth, mHeight);
    }

    // Draws the texture on to the specified rectangle.
    @Override
    public void draw(GLCanvas canvas, int x, int y, int w, int h) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float scaleX = (float) w / mWidth;
        float scaleY = (float) h / mHeight;

        synchronized (mImage) {
            syncFrame();
            for (int i = 0, n = mTiles.length; i < n; ++i) {
                Tile t = mTiles[i];
                src.set(0, 0, t.contentWidth, t.contentHeight);
                src.offset(t.offsetX, t.offsetY);
                mapRect(dest, src, 0, 0, x, y, scaleX, scaleY);
                src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
                canvas.drawTexture(t, src, dest);
            }
        }
    }

    // Draws a sub region of this texture on to the specified rectangle.
    @Override
    public void draw(GLCanvas canvas, RectF source, RectF target) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float x0 = source.left;
        float y0 = source.top;
        float x = target.left;
        float y = target.top;
        float scaleX = target.width() / source.width();
        float scaleY = target.height() / source.height();

        synchronized (mImage) {
            syncFrame();
            for (int i = 0, n = mTiles.length; i < n; ++i) {
                Tile t = mTiles[i];
                src.set(0, 0, t.contentWidth, t.contentHeight);
                src.offset(t.offsetX, t.offsetY);
                if (!src.intersect(source))
                    continue;
                mapRect(dest, src, x0, y0, x, y, scaleX, scaleY);
                src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
                canvas.drawTexture(t, src, dest);
            }
        }
    }

    // Draws a mixed color of this texture and a specified color onto the
    // a rectangle. The used color is: from * (1 - ratio) + to * ratio.
    public void drawMixed(GLCanvas canvas, int color, float ratio,
            int x, int y, int width, int height) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float scaleX = (float) width / mWidth;
        float scaleY = (float) height / mHeight;

        synchronized (mImage) {
            syncFrame();
            for (int i = 0, n = mTiles.length; i < n; ++i) {
                Tile t = mTiles[i];
                src.set(0, 0, t.contentWidth, t.contentHeight);
                src.offset(t.offsetX, t.offsetY);
                mapRect(dest, src, 0, 0, x, y, scaleX, scaleY);
                src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
                canvas.drawMixed(t, color, ratio, src, dest);
            }
        }
    }

    public void drawMixed(GLCanvas canvas, int color, float ratio,
            RectF source, RectF target) {
        RectF src = mSrcRect;
        RectF dest = mDestRect;
        float x0 = source.left;
        float y0 = source.top;
        float x = target.left;
        float y = target.top;
        float scaleX = target.width() / source.width();
        float scaleY = target.height() / source.height();

        synchronized (mImage) {
            syncFrame();
            for (int i = 0, n = mTiles.length; i < n; ++i) {
                Tile t = mTiles[i];
                src.set(0, 0, t.contentWidth, t.contentHeight);
                src.offset(t.offsetX, t.offsetY);
                if (!src.intersect(source))
                    continue;
                mapRect(dest, src, x0, y0, x, y, scaleX, scaleY);
                src.offset(t.borderSize - t.offsetX, t.borderSize - t.offsetY);
                canvas.drawMixed(t, color, ratio, src, dest);
            }
        }
    }

    @Override
    public boolean isOpaque() {
        return false;
    }

    public boolean isReady() {
        return mUploadIndex == mTiles.length;
    }

    public void setFrame(int frame) {
        synchronized (mImage) {
            mTargetFrame = frame;
            mImage.notify();
        }
    }

    public void setPause(boolean pause) {
        synchronized (mImage) {
            mPause = pause;
            if (!pause) {
                mImage.notify();
            }
        }
    }

    // Can be called in UI thread.
    public void recycle() {
        synchronized (mImage) {
            mRecycled = true;

            for (int i = 0, n = mTiles.length; i < n; ++i) {
                mTiles[i].free();
            }

            mImage.notify();

            if (!mRecycleLock) {
                mImage.recycle();
            }
        }
    }

    public interface Callback {
        void invalidateImage(Image image);
    }
}
/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.TypedArray;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;

import com.android.systemui.ExpandHelper;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;
import com.android.systemui.SwipeHelper;

import java.util.HashMap;

public class NotificationRowLayout 
        extends LinearLayout 
        implements SwipeHelper.Callback, ExpandHelper.Callback 
{
    private static final String TAG = "NotificationRowLayout";
    private static final boolean DEBUG = false;
    private static final boolean SLOW_ANIMATIONS = DEBUG;

    private static final int APPEAR_ANIM_LEN = SLOW_ANIMATIONS ? 5000 : 250;
    private static final int DISAPPEAR_ANIM_LEN = APPEAR_ANIM_LEN;

    boolean mAnimateBounds = true;

    Rect mTmpRect = new Rect();

    HashMap<View, ValueAnimator> mAppearingViews = new HashMap<View, ValueAnimator>();
    HashMap<View, ValueAnimator> mDisappearingViews = new HashMap<View, ValueAnimator>();

    private SwipeHelper mSwipeHelper;
    private ExpandHelper mExpandHelper;

    private Gefingerpoken mCurrentHelper;

    // Flag set during notification removal animation to avoid causing too much work until
    // animation is done
    boolean mRemoveViews = true;

    public NotificationRowLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationRowLayout(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        setLayoutTransition(new LayoutTransition());
        
        setOrientation(LinearLayout.VERTICAL);

        setMotionEventSplittingEnabled(false);

        if (DEBUG) {
            setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
                @Override
                public void onChildViewAdded(View parent, View child) {
                    Slog.d(TAG, "view added: " + child + "; new count: " + getChildCount());
                }
                @Override
                public void onChildViewRemoved(View parent, View child) {
                    Slog.d(TAG, "view removed: " + child + "; new count: " + (getChildCount() - 1));
                }
            });

            setBackgroundColor(0x80FF8000);
        }

        float densityScale = getResources().getDisplayMetrics().density;
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper = new SwipeHelper(SwipeHelper.X, this, densityScale, pagingTouchSlop);
        int minHeight = getResources().getDimensionPixelSize(R.dimen.notification_min_height);
        int maxHeight = getResources().getDimensionPixelSize(R.dimen.notification_max_height);
        mExpandHelper = new ExpandHelper(mContext, this, minHeight, maxHeight);
    }

    public void setLongPressListener(View.OnLongClickListener listener) {
        mSwipeHelper.setLongPressListener(listener);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) {
            mSwipeHelper.removeLongPressCallback();
        }
    }

    public void setAnimateBounds(boolean anim) {
        mAnimateBounds = anim;
    }

    private void logLayoutTransition() {
        Log.v(TAG, "layout " +
              (getLayoutTransition().isChangingLayout() ? "is " : "is not ") +
              "in transition and animations " +
              (getLayoutTransition().isRunning() ? "are " : "are not ") +
              "running.");
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onInterceptTouchEvent()");
        if (DEBUG) logLayoutTransition();

        MotionEvent cancellation = MotionEvent.obtain(ev);
        cancellation.setAction(MotionEvent.ACTION_CANCEL);

        if (mSwipeHelper.onInterceptTouchEvent(ev)) {
            if (DEBUG) Log.v(TAG, "will swipe");
            mCurrentHelper = mSwipeHelper;
            mExpandHelper.onInterceptTouchEvent(cancellation);
            return true;
        } else if (mExpandHelper.onInterceptTouchEvent(ev)) {
            if (DEBUG) Log.v(TAG, "will stretch");
            mCurrentHelper = mExpandHelper;
            mSwipeHelper.onInterceptTouchEvent(cancellation);
            return true;
        } else {
            mCurrentHelper = null;
            if (super.onInterceptTouchEvent(ev)) {
                if (DEBUG) Log.v(TAG, "intercepting ourselves");
                mSwipeHelper.onInterceptTouchEvent(cancellation);
                mExpandHelper.onInterceptTouchEvent(cancellation);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (DEBUG) Log.v(TAG, "onTouchEvent()");
        if (DEBUG) logLayoutTransition();
        if (mCurrentHelper != null) {
            return mCurrentHelper.onTouchEvent(ev);
        }
        return super.onTouchEvent(ev);
    }

    public boolean canChildBeDismissed(View v) {
        final View veto = v.findViewById(R.id.veto);
        return (veto != null && veto.getVisibility() != View.GONE);
    }

    public boolean canChildBeExpanded(View v) {
        Object isExpandable = v.getTag(R.id.expandable_tag);
        return isExpandable != null && isExpandable instanceof Boolean &&
                ((Boolean)isExpandable).booleanValue();
    }

    public void onChildDismissed(View v) {
        final View veto = v.findViewById(R.id.veto);
        if (veto != null && veto.getVisibility() != View.GONE && mRemoveViews) {
            veto.performClick();
        }
    }

    public void onBeginDrag(View v) {
        // We need to prevent the surrounding ScrollView from intercepting us now;
        // the scroll position will be locked while we swipe
        requestDisallowInterceptTouchEvent(true);
    }

    public void onDragCancelled(View v) {
    }

    public View getChildAtPosition(MotionEvent ev) {
        return getChildAtPosition(ev.getX(), ev.getY());
    }
    public View getChildAtPosition(float touchX, float touchY) {
        // find the view under the pointer, accounting for GONE views
        final int count = getChildCount();
        int y = 0;
        int childIdx = 0;
        View slidingChild;
        for (; childIdx < count; childIdx++) {
            slidingChild = getChildAt(childIdx);
            if (slidingChild.getVisibility() == GONE) {
                continue;
            }
            y += slidingChild.getMeasuredHeight();
            if (touchY < y) return slidingChild;
        }
        return null;
    }

    public View getChildContentView(View v) {
        return v;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float densityScale = getResources().getDisplayMetrics().density;
        mSwipeHelper.setDensityScale(densityScale);
        float pagingTouchSlop = ViewConfiguration.get(mContext).getScaledPagingTouchSlop();
        mSwipeHelper.setPagingTouchSlop(pagingTouchSlop);
    }


    /**
     * Sets a flag to tell us whether to actually remove views. Removal is delayed by setting this
     * to false during some animations to smooth out performance. Callers should restore the
     * flag to true after the animation is done, and then they should make sure that the views
     * get removed properly.
     */
    public void setViewRemoval(boolean removeViews) {
        mRemoveViews = removeViews;
    }

    public void dismissRowAnimated(View child) {
        dismissRowAnimated(child, 0);
    }

    public void dismissRowAnimated(View child, int vel) {
        mSwipeHelper.dismissChild(child, vel);
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        if (DEBUG) setWillNotDraw(false);
    }

    @Override
    public void onDraw(android.graphics.Canvas c) {
        super.onDraw(c);
        if (DEBUG) logLayoutTransition();
        if (DEBUG) {
            //Slog.d(TAG, "onDraw: canvas height: " + c.getHeight() + "px; measured height: "
            //        + getMeasuredHeight() + "px");
            c.save();
            c.clipRect(6, 6, c.getWidth() - 6, getMeasuredHeight() - 6,
                    android.graphics.Region.Op.DIFFERENCE);
            c.drawColor(0xFFFF8000);
            c.restore();
        }
    }
}

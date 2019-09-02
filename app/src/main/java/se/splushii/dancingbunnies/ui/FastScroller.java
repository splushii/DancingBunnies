package se.splushii.dancingbunnies.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;

import androidx.core.util.Consumer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import se.splushii.dancingbunnies.R;
import se.splushii.dancingbunnies.util.Util;

public class FastScroller extends LinearLayout {
    private static final String LC = Util.getLogContext(FastScroller.class);

    private View handle;
    private RecyclerView.OnScrollListener scrollListener;
    private RecyclerView recyclerView;
    private ViewHider handleHider;
    private FastScrollerBubble bubble;
    private ViewHider bubbleHider;

    private static final int ANIMATION_DURATION = 100;
    private static final int VIEW_HIDE_DELAY = 1000;
    private static final String SCALE_X = "scaleX";
    private static final String SCALE_Y = "scaleY";
    private static final String ALPHA = "alpha";
    private boolean touching = false;
    private float handleOffset = 0f;
    private boolean bubbleEnabled = true;
    private boolean recyclerViewReversed = false;
    private Consumer<Boolean> onHiddenAction;

    public FastScroller(Context context) {
        super(context);
        init(context);
    }

    public FastScroller(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        onHiddenAction = hidden -> {};
        setOrientation(HORIZONTAL);
        setClipChildren(false);
        inflate(context, R.layout.fastscroller, this);
        handle = findViewById(R.id.fastscroller_handle);
        handleHider = new ViewHider(handle, AnimationType.FADE);
        hideView(handle, AnimationType.FADE);
    }

    public void setRecyclerView(RecyclerView recyclerView) {
        this.recyclerView = recyclerView;
        scrollListener = new ScrollListener();
        recyclerView.addOnScrollListener(scrollListener);
    }

    public void setBubble(FastScrollerBubble bubble) {
        this.bubble = bubble;
        this.bubbleHider = new ViewHider(bubble, AnimationType.SCALE);
        hideView(bubble, AnimationType.SCALE);
    }

    public void onDestroy() {
        recyclerView.removeOnScrollListener(scrollListener);
        scrollListener = null;
    }

    public void enableBubble(boolean enabled) {
        bubbleEnabled = enabled;
        if (!enabled) {
            animateHide(bubbleHider, VIEW_HIDE_DELAY);
        }
    }

    public void setReversed(boolean reversed) {
        recyclerViewReversed = reversed;
    }

    public void setOnHidden(Consumer<Boolean> consumer) {
        onHiddenAction = consumer;
    }

    private enum AnimationType {
        FADE,
        SCALE
    }

    private void showView(View v, AnimationType animationType) {
        AnimatorSet animatorSet = new AnimatorSet();
        v.setPivotX(v.getWidth());
        v.setPivotY(v.getHeight());
        v.setVisibility(VISIBLE);
        ArrayList<Animator> animators = new ArrayList<>();
        switch (animationType) {
            case SCALE:
                animators.add(ObjectAnimator.ofFloat(v, SCALE_X, 0f, 1f)
                        .setDuration(ANIMATION_DURATION));
                animators.add(ObjectAnimator.ofFloat(v, SCALE_Y, 0f, 1f)
                        .setDuration(ANIMATION_DURATION));
                /* FALLTHRU */
            case FADE:
                animators.add(ObjectAnimator.ofFloat(v, ALPHA, 0f, 1f)
                        .setDuration(ANIMATION_DURATION));
                break;
        }
        animatorSet.playTogether(animators);
        animatorSet.start();
    }

    private void hideView(View v, AnimationType animationType) {
        int visibility = fastscrollerNeeded() ? INVISIBLE : GONE;
        AnimatorSet animatorSet = new AnimatorSet();
        v.setPivotX(v.getWidth());
        v.setPivotY(v.getHeight());
        ArrayList<Animator> animators = new ArrayList<>();
        switch (animationType) {
            case SCALE:
                animators.add(ObjectAnimator.ofFloat(v, SCALE_X, 1f, 0f)
                        .setDuration(ANIMATION_DURATION));
                animators.add(ObjectAnimator.ofFloat(v, SCALE_Y, 1f, 0f)
                        .setDuration(ANIMATION_DURATION));
                /* FALLTHRU */
            case FADE:
                animators.add(ObjectAnimator.ofFloat(v, ALPHA, 1f, 0f)
                        .setDuration(ANIMATION_DURATION));
                break;

        }
        animatorSet.playTogether(animators);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                v.setVisibility(visibility);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                super.onAnimationCancel(animation);
                v.setVisibility(visibility);
            }
        });
        animatorSet.start();
    }

    private void setPosition(float position) {
        int height = getHeight();
        float proportion = position / height;
        int handleHeight = handle.getHeight();
        int handleRange = height - handleHeight;
        int handlePos = getValueInRange(0, handleRange, (int) (handleRange * proportion));
        handle.setY(handlePos);
        if (bubble != null) {
            int bubbleHeight = bubble.getHeight();
            int bubbleRange = height - bubbleHeight;
            int bubblePos = getValueInRange(0, bubbleRange, (int) position - bubbleHeight);
            bubble.setY(bubblePos);
        }
    }

    private int getValueInRange(int min, int max, int value) {
        int minimum = Math.max(min, value);
        return Math.min(minimum, max);
    }

    private boolean fastscrollerNeeded() {
        boolean needed = false;
        if (recyclerView != null) {
            RecyclerView.Adapter adapter = recyclerView.getAdapter();
            if (adapter != null && adapter.getItemCount() > 50) {
                needed = true;
            }
        }
        onHiddenAction.accept(!needed);
        return needed;
    }

    /**
     * Handling events from RecyclerView
     */
    private class ScrollListener extends RecyclerView.OnScrollListener {
        int lastPos = 0;
        @Override
        public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
            if (!fastscrollerNeeded()) {
                hideView(handle, AnimationType.FADE);
                if (bubble != null) {
                    hideView(bubble, AnimationType.SCALE);
                }
                return;
            }
            if (bubble != null) {
                int pos = ((LinearLayoutManager) recyclerView.getLayoutManager()).findFirstCompletelyVisibleItemPosition();
                if (pos != lastPos) {
                    pos = pos >= 0 ? pos : 0;
                    lastPos = pos;
                    bubble.update(pos);
                }
            }

            animateShow(handle, handleHider, AnimationType.FADE);
            animateHide(handleHider, VIEW_HIDE_DELAY);

            if (!touching) {
                int scrollOffset = recyclerView.computeVerticalScrollOffset();
                int scrollRange = recyclerView.computeVerticalScrollRange();
                int visibleScrollRange = recyclerView.getHeight();
                int invisibleScrollRange = scrollRange - visibleScrollRange;
                float proportion = scrollOffset / (float) invisibleScrollRange;
                int height = getHeight();
                float handlePos = proportion * height;
                setPosition(handlePos);
            }
        }
    }

    /**
     * Handling events from FastScroller
     */
    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!fastscrollerNeeded()) {
            hideView(handle, AnimationType.FADE);
            if (bubble != null) {
                hideView(bubble, AnimationType.SCALE);
            }
            return super.onTouchEvent(event);
        }
        int action = event.getAction();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
            touching = true;
            setPosition(event.getY());
            animateShow(handle, handleHider, AnimationType.FADE);
            if (bubble != null && bubbleEnabled) {
                animateShow(bubble, bubbleHider, AnimationType.SCALE);
            }
            setRecyclerViewPosition();
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_UP) {
            touching = false;
            animateHide(handleHider, VIEW_HIDE_DELAY);
            if (bubble != null) {
                animateHide(bubbleHider, VIEW_HIDE_DELAY);
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void animateHide(ViewHider viewHider, int hideDelay) {
        getHandler().postDelayed(viewHider, hideDelay);
    }

    private void animateShow(View v, ViewHider viewHider, AnimationType animationType) {
        getHandler().removeCallbacks(viewHider);
        if (v.getVisibility() != VISIBLE) {
            showView(v, animationType);
        }
    }

    private class ViewHider implements Runnable {
        private final View view;
        private final AnimationType animationType;
        ViewHider(View v, AnimationType animationType) {
            this.view = v;
            this.animationType = animationType;
        }
        @Override
        public void run() {
            hideView(view, animationType);
        }
    }

    private void setRecyclerViewPosition() {
        if (recyclerView != null) {
            recyclerView.stopScroll();

            float newHandleOffset = handle.getY();
            if (newHandleOffset == handleOffset) {
                return;
            }
            handleOffset = newHandleOffset;
            int handleRange = getHeight() - handle.getHeight();
            int numItems = recyclerView.getAdapter().getItemCount();
            int position;
            if (newHandleOffset <= 0) {
                position = recyclerViewReversed ? numItems - 1 : 0;
            } else if (newHandleOffset >= handleRange) {
                position = recyclerViewReversed ? 0 : numItems - 1;
            } else {
                float offsetProportion = newHandleOffset / handleRange;
                int visibleItems = recyclerView.getChildCount();
                position = getValueInRange(
                        0,
                        numItems - 1,
                        (int) (offsetProportion * (numItems - visibleItems + 1))
                );
                position = recyclerViewReversed ? numItems - position : position;
            }
            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) recyclerView.getLayoutManager();
            linearLayoutManager.scrollToPositionWithOffset(position, 0);
        }
    }
}
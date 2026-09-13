package xyz.doikki.videoplayer.render;

import android.util.Log;
import android.view.View;

import xyz.doikki.videoplayer.player.VideoView;

public class MeasureHelper {

    private int mVideoWidth;

    private int mVideoHeight;

    private int mCurrentScreenScale;

    private int mVideoRotationDegree;

    public void setVideoRotation(int videoRotationDegree) {
        mVideoRotationDegree = videoRotationDegree;
    }

    public void setVideoSize(int width, int height) {
        mVideoWidth = width;
        mVideoHeight = height;
    }

    public void setScreenScale(int screenScale) {
        mCurrentScreenScale = screenScale;
    }

    /**
     * 注意：VideoView的宽高一定要定死，否者以下算法不成立
     */
    public int[] doMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mVideoRotationDegree == 90 || mVideoRotationDegree == 270) { // 软解码时处理旋转信息，交换宽高
            widthMeasureSpec = widthMeasureSpec + heightMeasureSpec;
            heightMeasureSpec = widthMeasureSpec - heightMeasureSpec;
            widthMeasureSpec = widthMeasureSpec - heightMeasureSpec;
        }

        int width = View.MeasureSpec.getSize(widthMeasureSpec);
        int height = View.MeasureSpec.getSize(heightMeasureSpec);

        if (mVideoHeight == 0 || mVideoWidth == 0) {
            return new int[]{width, height};
        }

        //如果设置了比例
        switch (mCurrentScreenScale) {
            case VideoView.SCREEN_SCALE_DEFAULT:
            default:
                if (mVideoWidth * height < width * mVideoHeight) {
                    width = height * mVideoWidth / mVideoHeight;
                } else if (mVideoWidth * height > width * mVideoHeight) {
                    height = width * mVideoHeight / mVideoWidth;
                }
                break;
            case VideoView.SCREEN_SCALE_ORIGINAL:
                width = mVideoWidth;
                height = mVideoHeight;
                break;
            case VideoView.SCREEN_SCALE_16_9:
                if (height > width / 16 * 9) {
                    height = width / 16 * 9;
                } else {
                    width = height / 9 * 16;
                }
                break;
            case VideoView.SCREEN_SCALE_4_3:
                if (height > width / 4 * 3) {
                    height = width / 4 * 3;
                } else {
                    width = height / 3 * 4;
                }
                break;
            case VideoView.SCREEN_SCALE_MATCH_PARENT:
                // 原实现误把 MeasureSpec 当成尺寸使用。MeasureSpec 是 (mode << 30) | size 的打包值,
                // 直接赋值会得到 1<<30 量级的天文数字,导致渲染视图被测量成远超屏幕的尺寸——
                // 表现为视频被极度拉伸/压缩、居中后大量内容落到屏幕可见区域之外。
                // 填充模式的语义就是"撑满容器",而 width/height 在方法开头已按 rotation 取过 getSize(),
                // 本身就是容器尺寸,保持原值即可。
                break;
            case VideoView.SCREEN_SCALE_CENTER_CROP:
                if (mVideoWidth * height > width * mVideoHeight) {
                    width = height * mVideoWidth / mVideoHeight;
                } else {
                    height = width * mVideoHeight / mVideoWidth;
                }
                break;
        }
        if (mVideoWidth > 0 && (sLastOutW != width || sLastOutH != height)) {
            Log.d("TVBoxDiag", "MeasureHelper scale=" + mCurrentScreenScale
                    + " video=" + mVideoWidth + "x" + mVideoHeight
                    + " out=" + width + "x" + height);
            sLastOutW = width;
            sLastOutH = height;
        }
        return new int[]{width, height};
    }

    private static int sLastOutW = -1;
    private static int sLastOutH = -1;
}

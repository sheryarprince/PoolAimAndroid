package com.team.aimpool.ai.team.ntpool;
import android.graphics.Bitmap;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
public class APoolScene {
 public static final int FORMAT_BGR=0, FORMAT_RGBA=1, IMAGE_MODE_BITMAP=0, IMAGE_MODE_BYTE_BUFFER=1;
 private Bitmap bitmap; private ByteBuffer byteBuffer; private int captureMode,format,height,imageMode,rotation,sceneCount,width,widthGap;
 private String identity,name; private boolean needResize; private AtomicInteger taskCount=new AtomicInteger();
 public APoolScene() {}
 public Bitmap getBitmap(){return bitmap;} public ByteBuffer getByteBuffer(){return byteBuffer;}
 public int getCaptureMode(){return captureMode;} public int getFormat(){return format;} public int getHeight(){return height;}
 public String getIdentity(){return identity;} public int getImageMode(){return imageMode;} public String getName(){return name;}
 public int getRotation(){return rotation;} public int getWidth(){return width;} public int getWidthGap(){return widthGap;} public boolean isNeedResize(){return needResize;}
 public void setBitmap(Bitmap v){bitmap=v;} public void setByteBuffer(ByteBuffer v){byteBuffer=v;} public void setCaptureMode(int v){captureMode=v;}
 public void setFormat(int v){format=v;} public void setHeight(int v){height=v;} public void setIdentity(String v){identity=v;}
 public void setImageMode(int v){imageMode=v;} public void setName(String v){name=v;} public void setNeedResize(boolean v){needResize=v;}
 public void setRotation(int v){rotation=v;} public void setWidth(int v){width=v;} public void setWidthGap(int v){widthGap=v;}
}
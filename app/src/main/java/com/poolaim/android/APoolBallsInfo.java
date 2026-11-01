package com.team.aimpool.ai.team.ntpool.typool;
import android.graphics.Point; import android.graphics.Rect;
public class APoolBallsInfo {
 private float line_angle,shortline_angle1,shortline_angle2; private Rect roi; private int scene_category,shortline1_len,shortline2_len; private Point white_ball,white_circle; private boolean white_circle_close_edge;
 public APoolBallsInfo(){} public float getLineAngle(){return line_angle;} public Rect getRoi(){return roi;} public int getSceneCategory(){return scene_category;}
 public float getShortlineAngle1(){return shortline_angle1;} public float getShortlineAngle2(){return shortline_angle2;} public float getShortlineLen1(){return shortline1_len;} public float getShortlineLen2(){return shortline2_len;}
 public Point getWhiteBall(){return white_ball;} public Point getWhiteCircle(){return white_circle;} public boolean getWhiteCircleCloseEdge(){return white_circle_close_edge;}
}
package com.example.roomscan.rendering

import android.opengl.GLES20
import com.example.roomscan.geometry.Corner
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 在 AR 画面上用线段把已采集的角点连起来,并在每个角点画一个小方块。
 * 让用户实时看到"已经点了哪些墙"。纯 GLES20,无依赖。
 */
class OverlayRenderer {

    private var program = 0
    private var positionAttrib = 0
    private var mvpUniform = 0
    private var colorUniform = 0

    fun createOnGlThread() {
        val vs = ShaderUtil.load(GLES20.GL_VERTEX_SHADER, VS)
        val fs = ShaderUtil.load(GLES20.GL_FRAGMENT_SHADER, FS)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        positionAttrib = GLES20.glGetAttribLocation(program, "a_Position")
        mvpUniform = GLES20.glGetUniformLocation(program, "u_Mvp")
        colorUniform = GLES20.glGetUniformLocation(program, "u_Color")
    }

    /**
     * @param viewProj  view * projection(ARCore 给出的相机矩阵乘积)
     * @param corners   已采集角点(世界坐标)
     * @param closeLoop 是否把最后一点连回第一点
     */
    fun draw(viewProj: FloatArray, corners: List<Corner>, closeLoop: Boolean) {
        if (corners.isEmpty()) return
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(mvpUniform, 1, false, viewProj, 0)
        GLES20.glLineWidth(6f)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST) // 让线始终可见,不被地面"遮住"

        // 1) 墙线
        if (corners.size >= 2) {
            val pts = ArrayList<Float>(corners.size * 3 + 3)
            corners.forEach { pts += it.x; pts += it.y + 0.01f; pts += it.z } // 稍微抬高避免 z-fighting
            if (closeLoop) { pts += corners[0].x; pts += corners[0].y + 0.01f; pts += corners[0].z }
            upload(pts.toFloatArray())
            GLES20.glUniform4f(colorUniform, 0.12f, 0.53f, 0.90f, 1f)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP, 0, pts.size / 3)
        }

        // 2) 角点:每个画一个水平小十字(5cm)
        val s = 0.05f
        val cross = ArrayList<Float>()
        corners.forEach { c ->
            cross += c.x - s; cross += c.y + 0.01f; cross += c.z
            cross += c.x + s; cross += c.y + 0.01f; cross += c.z
            cross += c.x;     cross += c.y + 0.01f; cross += c.z - s
            cross += c.x;     cross += c.y + 0.01f; cross += c.z + s
        }
        upload(cross.toFloatArray())
        GLES20.glUniform4f(colorUniform, 1f, 0.76f, 0.03f, 1f)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, cross.size / 3)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun upload(data: FloatArray) {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(data); position(0) }
        GLES20.glVertexAttribPointer(positionAttrib, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glEnableVertexAttribArray(positionAttrib)
    }

    companion object {
        private const val VS = """
            uniform mat4 u_Mvp;
            attribute vec4 a_Position;
            void main() { gl_Position = u_Mvp * a_Position; }
        """
        private const val FS = """
            precision mediump float;
            uniform vec4 u_Color;
            void main() { gl_FragColor = u_Color; }
        """
    }
}

/*******************************************************************************
 *  Copyright (c) 2010 Weltevree Beheer BV, Remain Software & Industrial-TSI
 * 
 * All rights reserved. 
 * This program and the accompanying materials are made available under the terms of
 * the Eclipse Public License v1.0 which accompanies this distribution, and is
 * available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *   Wim S. Jongman - initial API and implementation
 ******************************************************************************/
package org.eclipse.nebula.widgets.oscilloscope;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;

public class Oscilloscope extends Canvas {

	public static final int DEFAULT_WIDTH = 60;
	public static final int DEFAULT_HEIGHT = 40;
	public static final int DEFAULT_TAILFADE = 25;

	private Color bg;
	private Color fg;
	private int cursor = 50;
	private int width = DEFAULT_WIDTH;
	private int height = DEFAULT_HEIGHT;
	private int base;
	private IntegerFiFoCircularStack stack;
	private int tailSize;
	private int lineWidth = 1;
	private boolean percentage = false;
	private int[] tail;
	private int originalTailSize;
	private boolean steady;
	private int tailFade = 25;
	private boolean fade;
	private boolean connect;
	private int originalSteadyPosition = STEADYPOSITION_75PERCENT;

	/**
	 * This set of values will draw a figure that is similar to the heart beat
	 * that you see on hospital monitors.
	 */
	public static final int[] HEARTBEAT = new int[] { 2, 10, 2, -16, 16, 50,
			64, 50, 32, 14, -16, -38, -56, -54, -32, -10, 8, 6, 6, -2, 6, 4, 2,
			0, 0, 6, 8, 6 };
	/**
	 * The default tail size is 75% of the width.
	 */
	public static final int TAILSIZE_DEFAULT = -3;

	/**
	 * Will draw a maximum tail.
	 */
	public static final int TAILSIZE_MAX = -1;

	/**
	 * Steady position @ 75% of graph.
	 */
	public static final int STEADYPOSITION_75PERCENT = -1;

	/**
	 * Will draw a tail from the left border but is only valid of
	 * {@link #setSteady(boolean, int)} was set to true, will default to
	 * {@link #TAILSIZE_MAX} otherwise.
	 */
	public static final int TAILSIZE_FILL = -2;

	/**
	 * The stack will not overflow if you push too many values into it but it
	 * will rotate and overwrite the older values. Think of the stack as a
	 * closed loop with one hole to push values in and one that lets them out.
	 * 
	 */
	public class IntegerFiFoCircularStack {
		final private int[] stack;
		private int top;
		private int bottom;
		private final int capacity;

		/**
		 * Creates a stack with the indicated capacity.
		 * 
		 * @param capacity
		 */
		public IntegerFiFoCircularStack(int capacity) {
			this.capacity = capacity;
			stack = new int[capacity];
			top = 0;
			bottom = 0;
		}

		/**
		 * Creates stack with the indicated capacity and copies the old stack
		 * into the new stack.
		 * 
		 * @param capacity
		 * @param oldStack
		 */
		public IntegerFiFoCircularStack(int capacity,
				IntegerFiFoCircularStack oldStack) {
			this(capacity);
			while (!oldStack.isEmpty())
				push(oldStack.pop(0));
		}

		/**
		 * Clears the stack.
		 */
		public void clear() {
			synchronized (stack) {
				for (int i = 0; i < stack.length; i++) {
					stack[i] = 0;
				}
				top = 0;
				bottom = 0;
			}
		}

		/**
		 * Puts a value on the stack.
		 * 
		 * @param value
		 */
		public void push(int value) {
			if (top == capacity - 1)
				top = 0;
			stack[top++] = value * -1;
		}

		/**
		 * Returns the oldest value from the stack. Returns the supplied entry
		 * if the stack is empty.
		 * 
		 * @param valueIfEmpty
		 * @return int
		 */
		public int pop(int valueIfEmpty) {
			if (bottom == top)
				return bottom = top = valueIfEmpty;
			if (bottom == capacity - 1)
				bottom = 0;

			return stack[bottom++];
		}

		/**
		 * 
		 * @return boolean
		 */
		public boolean isEmpty() {
			return bottom == top;
		}

	}

	/**
	 * Creates a new Oscilloscope.
	 * 
	 * @param parent
	 * @param style
	 */
	public Oscilloscope(Composite parent, int style) {
		super(parent, SWT.DOUBLE_BUFFERED | style);

		bg = new Color(null, 0, 0, 0);
		setBackground(bg);

		fg = new Color(null, 255, 255, 255);
		setForeground(fg);

		addDisposeListener(new DisposeListener() {
			public void widgetDisposed(DisposeEvent e) {
				Oscilloscope.this.widgetDisposed(e);
			}
		});

		addPaintListener(new PaintListener() {
			public void paintControl(PaintEvent e) {
				Oscilloscope.this.paintControl(e);
			}
		});

		addControlListener(new ControlListener() {
			public void controlResized(ControlEvent e) {
				Oscilloscope.this.controlResized(e);
			}

			public void controlMoved(ControlEvent e) {
				Oscilloscope.this.controlMoved(e);
			}
		});

		setTailSize(TAILSIZE_DEFAULT);
	}

	protected void controlMoved(ControlEvent e) {

	}

	protected void controlResized(ControlEvent e) {
		setSizeInternal(getSize().x, getSize().y);
		if (getBounds().width > 0) {
			setTailSizeInternal();
			setSteady(steady, originalSteadyPosition);
		}
	}

	/**
	 * Returns the size of the tail.
	 * 
	 * @return int
	 * @see #setTailSize(int)
	 * @see #TAILSIZE_DEFAULT
	 * @see #TAILSIZE_FILL
	 * @see #TAILSIZE_MAX
	 * 
	 */
	public int getTailSize() {
		return tailSize;
	}

	private void setSizeInternal(int width, int height) {
		this.width = width;
		this.height = height;
		base = height / 2;
		if (stack == null)
			stack = new IntegerFiFoCircularStack(width);
		else
			stack = new IntegerFiFoCircularStack(width, stack);
	}

	protected void widgetDisposed(DisposeEvent e) {
		bg.dispose();
		fg.dispose();
	}

	protected void paintControl(PaintEvent e) {

		if (tailSize <= 0) {
			stack.pop(0);
			return;
		}

		GC gc = e.gc;
		gc.setAntialias(SWT.ON);
		gc.setLineWidth(getLineWidth());

		if (!isSteady())
			cursor++;
		if (cursor >= width)
			cursor = 0;

		// Draw
		int tailIndex = 1;
		int[] line1 = new int[tailSize * 4];
		int[] line2 = new int[tailSize * 4];

		if (isPercentage())
			tail[tailSize] = ((getBounds().height / 2) * stack.pop(0) / 100);
		else
			tail[tailSize] = stack.pop(0);

		int splitPos = tailSize * 4;
		for (int i = 0; i < tailSize; i++) {

			int posx = cursor - tailSize + i;
			int pos = i * 4;
			if (posx < 0) {
				posx += width;
				line1[pos] = posx - 1;

				line1[pos + 1] = base + (isSteady() ? 0 : tail[tailIndex - 1]);
				line1[pos + 2] = posx;
				line1[pos + 3] = base + (isSteady() ? 0 : tail[tailIndex]);
			}

			else {
				if (splitPos == tailSize * 4)
					splitPos = pos;
				line2[pos] = posx - 1;
				line2[pos + 1] = base + tail[tailIndex - 1];
				line2[pos + 2] = posx;
				line2[pos + 3] = (base + tail[tailIndex]);
			}
			tail[tailIndex - 1] = tail[tailIndex++];
		}

		int[] l1 = new int[splitPos];
		System.arraycopy(line1, 0, l1, 0, l1.length);
		int[] l2 = new int[(tailSize * 4) - splitPos];
		System.arraycopy(line2, splitPos, l2, 0, l2.length);

		// Fade tail
		if (isFade()) {
			gc.setAlpha(0);
			double fade = 0;
			double fadeOutStep = (double) 125
					/ (double) ((getTailSize() * (getTailFade()) / 100));
			for (int i = 0; i < l1.length - 4;) {
				fade += (fadeOutStep / 2);
				setAlpha(gc, fade);
				gc.drawLine(l1[i], l1[i + 1], l1[i + 2], l1[i + 3]);
				i += 2;
			}

			for (int i = 0; i < l2.length - 4;) {
				fade += (fadeOutStep / 2);
				setAlpha(gc, fade);
				gc.drawLine(l2[i], l2[i + 1], l2[i + 2], l2[i + 3]);
				i += 2;
			}

		} else {
			gc.drawPolyline(l1);
			gc.drawPolyline(l2);
		}

		// Connects the head with the tail
		if (originalTailSize == TAILSIZE_MAX && l1.length > 0 && l2.length > 0
				&& !isFade() && isConnect()) {
			gc.drawLine(l2[l2.length - 2], l2[l2.length - 1], l1[0], l1[1]);
		}
	}

	/**
	 * @return boolean, true if the tail and the head of the graph must be
	 *         connected.
	 */
	public boolean isConnect() {
		return connect;
	}

	/**
	 * Connects head and tail only if tail size is {@link #TAILSIZE_MAX} and no
	 * fading.
	 * 
	 * @param connectHeadAndTail
	 */
	public void setConnect(boolean connectHeadAndTail) {
		this.connect = connectHeadAndTail;
	}

	/**
	 * @see #setFade(boolean)
	 * @return boolean fade
	 */
	public boolean isFade() {
		return fade;
	}

	/**
	 * Sets fade mode so that a percentage of the tail will be faded out at the
	 * costs of extra CPU utilization (no beauty without pain or as the Dutch
	 * say: "Wie mooi wil gaan moet pijn doorstaan"). The reason for this is
	 * that each pixel must be drawn separately with alpha faded in instead of
	 * the elegant {@link GC#drawPolygon(int[])} routine which does not support
	 * alpha blending.
	 * <p>
	 * In addition to this, set the percentage of tail that must be faded out
	 * {@link #setTailFade(int)}.
	 * 
	 * @param boolean fade
	 * @see #setTailFade(int)
	 */
	public void setFade(boolean fade) {
		this.fade = fade;
	}

	private void setAlpha(GC gc, double fade) {

		if (gc.getAlpha() == fade)
			return;
		if (fade >= 255)
			gc.setAlpha(255);
		else
			gc.setAlpha((int) fade);
	}

	/**
	 * gets the percentage of tail that must be faded out.
	 * 
	 * @return int percentage
	 * @see #setFade(boolean)
	 */
	public int getTailFade() {
		return tailFade;
	}

	/**
	 * @return boolean steady indicator
	 * @see Oscilloscope#setSteady(boolean)
	 */
	public boolean isSteady() {
		return steady;
	}

	/**
	 * Set a bunch of values that will be drawn. The values will be stored in a
	 * stack and popped once a value is needed. The size of the stack is the
	 * width of the widget. If you resize the widget, the old stack will be
	 * copied into a new stack with the new capacity.
	 * 
	 * @param values
	 */
	public void setValues(int[] values) {

		if (getBounds().width <= 0)
			return;

		if (!super.isVisible())
			return;

		if (stack == null)
			stack = new IntegerFiFoCircularStack(width);
		for (int i = 0; i < values.length; i++) {
			stack.push(values[i]);
		}
	}

	/**
	 * Sets a value to be drawn relative to the middle of the widget. Supply a
	 * positive or negative value.
	 * 
	 * @param value
	 */
	public void setValue(int value) {
		if (getBounds().width <= 0)
			return;

		if (!super.isVisible())
			return;

		if (stack.capacity > 0)
			stack.push(value);
	}

	/**
	 * The tail size defaults to TAILSIZE_DEFAULT which is 75% of the width.
	 * Setting it with TAILSIZE_MAX will leave one pixel between the tail and
	 * the head. All values are absolute except TAILSIZE*. If the width is
	 * smaller then the tail size then the tail size will behave like
	 * TAILSIZE_MAX.
	 * 
	 * @param int size
	 * @see #getTailSize()
	 * @see #TAILSIZE_DEFAULT
	 * @see #TAILSIZE_FILL
	 * @see #TAILSIZE_MAX
	 */
	public void setTailSize(int size) {
		if (originalTailSize != size) {
			tailSizeCheck(size);
			originalTailSize = size;
			setTailSizeInternal();
		}
	}

	private void tailSizeCheck(int size) {
		if (size < -3 || size == 0)
			throw new RuntimeException("Invalid tail size " + size);
	}

	private void setTailSizeInternal() {
		if (originalTailSize == TAILSIZE_DEFAULT) {
			tail = new int[(width / 4) * 3];
			tailSize = (width / 4) * 3;
			tailSize--;
		} else if (originalTailSize == TAILSIZE_MAX || originalTailSize > width) {
			tail = new int[width - 2 + 1];
			tailSize = width - 2;
		} else if (tailSize != originalTailSize) {
			tail = new int[originalTailSize + 1];
			tailSize = originalTailSize;
		}
	}

	public Point computeSize(int wHint, int hHint, boolean changed) {

		if (wHint != SWT.DEFAULT)
			width = wHint;
		if (hHint != SWT.DEFAULT)
			height = hHint;

		setSizeInternal(width, height);

		return new Point(width + 2, height + 2);
	}

	public boolean needsRedraw() {
		return isDisposed() ? false : true;
	}

	/**
	 * Sets the line width. A value equal or below zero is ignored. The default
	 * width is 1.
	 * 
	 * @param int lineWidth
	 */
	public void setLineWidth(int lineWidth) {
		if (lineWidth > 0)
			this.lineWidth = lineWidth;
	}

	/**
	 * @return int, the width of the line.
	 * @see #setLineWidth(int)
	 */
	public int getLineWidth() {
		return lineWidth;
	}

	/**
	 * If set to true then the values are treated as percentages rather than
	 * absolute values. This will scale the amplitudes if the control is
	 * resized. Default is false.
	 * 
	 * @param boolean percentage
	 */
	public void setPercentage(boolean percentage) {
		this.percentage = percentage;
	}

	/**
	 * @return boolean
	 * @see #setPercentage(boolean)
	 */
	public boolean isPercentage() {
		return percentage;
	}

	/**
	 * If steady is true the graph will draw on a steady position instead of
	 * advancing.
	 * 
	 * @param steady
	 * @param steadyPosition
	 */
	public void setSteady(boolean steady, int steadyPosition) {
		this.steady = steady;
		this.originalSteadyPosition = steadyPosition;
		if (steady)
			if (steadyPosition == STEADYPOSITION_75PERCENT)
				this.cursor = (int) ((double) width * (double) 0.75);
			else if (steadyPosition > 0 && steadyPosition < width)
				this.cursor = steadyPosition;
	}

	/**
	 * Sets the percentage of tail that must be faded out. If you supply 100
	 * then the tail is faded out all the way to the top. The effect will become
	 * increasingly less obvious.
	 * 
	 * @param tailFade
	 */
	public void setTailFade(int tailFade) {
		if (tailFade > 100)
			tailFade = 100;
		if (tailFade < 1)
			tailFade = 1;
		this.tailFade = tailFade;
	}
}
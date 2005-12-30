/*
 * {{{ header & license
 * Copyright (c) 2005 Wisconsin Court System
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public License
 * as published by the Free Software Foundation; either version 2.1
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 * }}}
 */
package org.xhtmlrenderer.layout;

import java.awt.Dimension;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.xhtmlrenderer.css.constants.CSSName;
import org.xhtmlrenderer.css.style.CssContext;
import org.xhtmlrenderer.render.BlockBox;
import org.xhtmlrenderer.render.Box;
import org.xhtmlrenderer.render.FloatedBlockBox;
import org.xhtmlrenderer.render.InlineBox;
import org.xhtmlrenderer.render.RenderingContext;
import org.xhtmlrenderer.render.ViewportBox;

public class Layer {
    private Layer parent;
    private boolean stackingContext;
    private List children;
    private Box master;
    
    private Box end;

    private List floats;

    private boolean fixedBackground;
    
    private boolean inline;
    
    public Layer(Box master) {
        this(null, master);
        setStackingContext(true);
    }

    public Layer(Layer parent, Box master) {
        this.parent = parent;
        this.master = master;
        setStackingContext(!master.getStyle().isAutoZIndex());
        master.setLayer(this);
        master.setContainingLayer(this);
    }

    public Layer getParent() {
        return parent;
    }

    public boolean isStackingContext() {
        return stackingContext;
    }

    public void setStackingContext(boolean stackingContext) {
        this.stackingContext = stackingContext;
    }

    public int getZIndex() {
        return (int) master.getStyle().getCalculatedStyle().asFloat(CSSName.Z_INDEX);
    }

    public Box getMaster() {
        return master;
    }

    public synchronized void addChild(Layer layer) {
        if (children == null) {
            children = new ArrayList();
        }
        children.add(layer);
    }

    public void addFloat(FloatedBlockBox floater, BlockFormattingContext bfc) {
        if (floats == null) {
            floats = new ArrayList();
        }

        floats.add(floater);
        
        floater.setDrawingLayer(this);
    }

    public void removeFloat(FloatedBlockBox floater) {
        if (floats != null) {
            floats.remove(floater);
        }
    }

    private void paintFloats(RenderingContext c) {
        if (floats != null) {
            for (int i = floats.size() - 1; i >= 0; i--) {
                FloatedBlockBox floater = (FloatedBlockBox) floats.get(i);
                paintAsLayer(c, floater);
            }
        }
    }

    private void paintLayers(RenderingContext c, List layers) {
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = (Layer) layers.get(i);
            layer.paint(c, getMaster().getAbsX(), getMaster().getAbsY());
        }
    }
    
    private static final int POSITIVE = 1;
    private static final int ZERO = 2;
    private static final int NEGATIVE = 3;
    private static final int AUTO = 4;
    
    private List collectLayers(int which) {
        List result = new ArrayList();
        
        if (which != AUTO) {
            result.addAll(getStackingContextLayers(which));
        }
        
        List children = getChildren();
        for (int i = 0; i < children.size(); i++) {
            Layer child = (Layer)children.get(i);
            if (! child.isStackingContext()) {
                if (which == AUTO) {
                    result.add(child);
                } 
                result.addAll(child.collectLayers(which));
            }
        }
        
        return result;
    }
    
    private List getStackingContextLayers(int which) {
        List result = new ArrayList();
        
        List children = getChildren();
        for (int i = 0; i < children.size(); i++) {
            Layer target = (Layer)children.get(i);

            if (target.isStackingContext()) {
                if (which == NEGATIVE && target.getZIndex() < 0) {
                    result.add(target);
                } else if (which == POSITIVE && target.getZIndex() > 0) {
                    result.add(target);
                } else if (which == ZERO) {
                    result.add(target);
                }
            }
        }
        
        return result;
    }
    
    private List getSortedLayers(int which) {
        List result = collectLayers(which);
        
        Collections.sort(result, new ZIndexComparator());
        
        return result;
    }
    
    private static class ZIndexComparator implements Comparator {
        public int compare(Object o1, Object o2) {
            Layer l1 = (Layer)o1;
            Layer l2 = (Layer)o2;
            return l1.getZIndex() - l2.getZIndex();
        }
    }
    
    private void paintBackgroundsAndBorders(RenderingContext c, List blocks) {
        for (Iterator i = blocks.iterator(); i.hasNext();) {
            BlockBox box = (BlockBox) i.next();
            box.paintBackground(c);
            box.paintBorder(c);
            if (c.debugDrawBoxes()) {
                box.paintDebugOutline(c);
            }
        }
    }

    private void paintInlineContent(RenderingContext c, List lines) {
        for (Iterator i = lines.iterator(); i.hasNext();) {
            InlinePaintable paintable = (InlinePaintable) i.next();
            paintable.paintInline(c);
        }
    }
    
    public Dimension getPaintingDimension(LayoutContext c) {
        return calcPaintingDimension(c);
    }

    public void paint(RenderingContext c, int originX, int originY) {
        if (getMaster().getStyle().isFixed()) {
            positionFixedLayer(c);
        }

        if (! isInline() && ((BlockBox)getMaster()).isReplaced()) {
            paintReplacedElement(c, (BlockBox)getMaster());
        } else {
            List blocks = new ArrayList();
            List lines = new ArrayList();
    
            BoxCollector collector = new BoxCollector();
            collector.collect(c, c.getGraphics().getClip(), this, blocks, lines);
    
            // TODO root layer needs to be handled correctly (paint over entire canvas)
            if (! isInline()) {
                paintLayerBackgroundAndBorder(c);
                if (c.debugDrawBoxes()) {
                    ((BlockBox)getMaster()).paintDebugOutline(c);
                }
            }
            
            if (isRootLayer() || isStackingContext()) {
                paintLayers(c, getSortedLayers(NEGATIVE));
            }
    
            paintBackgroundsAndBorders(c, blocks);
            paintFloats(c);
            paintListMarkers(c, blocks);
            paintInlineContent(c, lines);
            paintReplacedElements(c, blocks);
    
            if (isRootLayer() || isStackingContext()) {
                paintLayers(c, collectLayers(AUTO));
                // TODO z-index: 0 layers should be painted atomically
                paintLayers(c, getSortedLayers(ZERO));
                paintLayers(c, getSortedLayers(POSITIVE));
            }
        }
    }
    
    public void paintAsLayer(RenderingContext c, BlockBox startingPoint) {
        if (startingPoint.isReplaced()) {
            paintReplacedElement(c, startingPoint);
        } else {
            List blocks = new ArrayList();
            List lines = new ArrayList();
    
            BoxCollector collector = new BoxCollector();
            collector.collect(c, c.getGraphics().getClip(), 
                    this, startingPoint, blocks, lines);
    
            paintBackgroundsAndBorders(c, blocks);
            paintListMarkers(c, blocks);
            paintInlineContent(c, lines);
            paintReplacedElements(c, blocks);
        }
    }    

    private void paintListMarkers(RenderingContext c, List blocks) {
        for (Iterator i = blocks.iterator(); i.hasNext();) {
            BlockBox box = (BlockBox) i.next();
            box.paintListMarker(c);
        }
    }
    
    private void paintReplacedElements(RenderingContext c, List blocks) {
        for (Iterator i = blocks.iterator(); i.hasNext();) {
            BlockBox box = (BlockBox) i.next();
            if (box.isReplaced()) {
                paintReplacedElement(c, box);
            }
        }
    }

    private void positionFixedLayer(RenderingContext c) {
        Rectangle rect = c.getFixedRectangle();
        rect.translate(-1, -1);

        Box fixed = getMaster();

        fixed.x = 0;
        fixed.y = -rect.y;
        fixed.setAbsX(0);
        fixed.setAbsY(0);

        fixed.setContainingBlock(new ViewportBox(rect));
        fixed.positionAbsolute(c);
    }

    private void paintLayerBackgroundAndBorder(RenderingContext c) {
        if (getMaster() instanceof BlockBox) {
            BlockBox box = (BlockBox) getMaster();
            box.paintBackground(c);
            box.paintBorder(c);
        }
    }
    
    private void paintReplacedElement(RenderingContext c, BlockBox replaced) {
        if (! c.isInteractive()) {
            replaced.component.paint(c.getGraphics());
        }
    }
    
    public boolean isRootLayer() {
        return getParent() == null && isStackingContext();
    }
    
    private void moveIfGreater(Dimension result, Dimension test) {
        if (test.width > result.width) {
            result.width = test.width;
        }
        if (test.height > result.height) {
            result.height = test.height;
        }
    }
    
    private Dimension calcPaintingDimension(LayoutContext c) {
        Dimension result = scanLayer(c);
        
        List children = getChildren();
        for (int i = 0; i < children.size(); i++) {
            Layer child = (Layer)children.get(i);
            
            if (child.getMaster().getStyle().isFixed()) {
                continue;
            } else if (child.getMaster().getStyle().isAbsolute()) {
                Dimension dim = child.scanLayer(c);
                moveIfGreater(result, dim);
            } 
        }
        
        return result;
    }

    // TODO block.renderIndex = c.getNewRenderIndex();
    private Dimension scanLayer(LayoutContext c) {
        return scanLayerHelper(c, getMaster());
    }
    
    private Dimension scanLayerHelper(final LayoutContext c, final Box box) {
        Rectangle bounds = box.getBounds(box.getAbsX(), box.getAbsY(), c, 0, 0);
        final Dimension result = 
            new Dimension(bounds.x + bounds.width, bounds.y + bounds.height);
        
        if (! (box instanceof InlineBox)) {
            if (box instanceof BlockBox && ((BlockBox)box).getPersistentBFC() != null) {
                ((BlockBox)box).getPersistentBFC().getFloatManager().performFloatOperation(
                        new FloatManager.FloatOperation() {
                            public void operate(Box floater) {
                                Dimension dim = scanLayerHelper(c, floater);
                                moveIfGreater(result, dim);
                            }
                        });
            }
            
            for (int i = 0; i < box.getChildCount(); i++) {
                Box child = (Box) box.getChild(i);
                Dimension offset = scanLayerHelper(c, child);
                moveIfGreater(result, offset);
            }
        } else {
            InlineBox iB = (InlineBox)box;
            for (int i = 0; i < iB.getInlineChildCount(); i++) {
                Object obj = iB.getInlineChild(i);
                if (obj instanceof Box) {
                    Dimension offset = scanLayerHelper(c, (Box)obj);
                    moveIfGreater(result, offset);
                } 
            }
        }
        
        return result;
    }

    public void positionChildren(CssContext cssCtx) {
        for (Iterator i = getChildren().iterator(); i.hasNext();) {
            Layer child = (Layer) i.next();

            child.finalizePosition(cssCtx);
        }
    }
    
    private void finalizePosition(CssContext cssCtx) {
        if (getMaster().getStyle().isAbsolute()) {
            getMaster().positionAbsolute(cssCtx);
        } else if (getMaster().getStyle().isRelative() && isInline()) {
            getMaster().positionRelative(cssCtx);
        }
    }

    private boolean containsFixedLayer() {
        for (Iterator i = getChildren().iterator(); i.hasNext();) {
            Layer child = (Layer) i.next();

            if (child.getMaster().getStyle().isFixed()) {
                return true;
            }
        }
        return false;
    }

    public boolean containsFixedContent() {
        return fixedBackground || containsFixedLayer();
    }

    public void setFixedBackground(boolean b) {
        this.fixedBackground = b;
    }

    public synchronized List getChildren() {
        return children == null ? Collections.EMPTY_LIST : Collections.unmodifiableList(children);
    }

    private void remove(Layer layer) {
        boolean removed = false;
        
        if (children != null) {
            for (Iterator i = children.iterator(); i.hasNext(); ) {
                Layer child = (Layer)i.next();
                if (child == layer) {
                    removed = true;
                    i.remove();
                    break;
                }
            }
        }
        
        if (! removed) {
            throw new RuntimeException("Could not find layer to remove");
        }
    }
    
    public void detach() {
        if (getParent() != null) {
            getParent().remove(this);
        }
    }

    public boolean isInline() {
        return inline;
    }

    public void setInline(boolean inline) {
        this.inline = inline;
    }

    public Box getEnd() {
        return end;
    }

    public void setEnd(Box end) {
        this.end = end;
    }
}

// Image and UI-dump helper for scripts/design/nw.sh. Run in source-file mode: java DesignTool.java <cmd> ...
//   jpeg IN.png OUT.jpg MAX_WIDTH QUALITY      scaled JPEG copy (repo copy and the preview Claude views)
//   crop IN.png OUT.png X Y W H [SCALE]        region at native pixels (or scaled), for detail views
//   px IN.png X Y [X Y ...]                    hex color at each point
//   ui IN.xml                                  compact list of visible UI nodes from a uiautomator dump
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

public class DesignTool {
  public static void main(String[] a) throws Exception {
    switch (a.length == 0 ? "" : a[0]) {
      case "jpeg" -> jpeg(a[1], a[2], Integer.parseInt(a[3]), Float.parseFloat(a[4]) / 100f);
      case "crop" -> crop(a);
      case "px" -> px(a);
      case "ui" -> ui(a[1]);
      default -> {
        System.err.println("usage: jpeg|crop|px|ui ...");
        System.exit(2);
      }
    }
  }

  static BufferedImage read(String path) throws Exception {
    BufferedImage img = ImageIO.read(new File(path));
    if (img == null) throw new IllegalArgumentException("not an image: " + path);
    return img;
  }

  static BufferedImage scaled(BufferedImage src, int w, int h) {
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
    g.drawImage(src, 0, 0, w, h, null);
    g.dispose();
    return out;
  }

  static void jpeg(String in, String out, int maxWidth, float quality) throws Exception {
    BufferedImage src = read(in);
    int w = Math.min(maxWidth, src.getWidth());
    int h = Math.round(src.getHeight() * (w / (float) src.getWidth()));
    BufferedImage img = scaled(src, w, h);
    Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
    ImageWriter writer = it.next();
    ImageWriteParam p = writer.getDefaultWriteParam();
    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
    p.setCompressionQuality(quality);
    File f = new File(out);
    f.delete();
    try (ImageOutputStream os = ImageIO.createImageOutputStream(f)) {
      writer.setOutput(os);
      writer.write(null, new IIOImage(img, null, null), p);
    }
    writer.dispose();
    System.out.println(out + " " + w + "x" + h + " from " + src.getWidth() + "x" + src.getHeight() + " (" + f.length() / 1024 + " KB)");
  }

  static void crop(String[] a) throws Exception {
    BufferedImage src = read(a[1]);
    int x = Integer.parseInt(a[3]), y = Integer.parseInt(a[4]);
    int w = Math.min(Integer.parseInt(a[5]), src.getWidth() - x);
    int h = Math.min(Integer.parseInt(a[6]), src.getHeight() - y);
    float s = a.length > 7 ? Float.parseFloat(a[7]) : 1f;
    BufferedImage region = src.getSubimage(x, y, w, h);
    BufferedImage out = scaled(region, Math.max(1, Math.round(w * s)), Math.max(1, Math.round(h * s)));
    ImageIO.write(out, "png", new File(a[2]));
    System.out.println(a[2] + " " + out.getWidth() + "x" + out.getHeight());
  }

  static void px(String[] a) throws Exception {
    BufferedImage src = read(a[1]);
    for (int i = 2; i + 1 < a.length; i += 2) {
      int x = Integer.parseInt(a[i]), y = Integer.parseInt(a[i + 1]);
      System.out.printf("%d,%d #%06X%n", x, y, src.getRGB(x, y) & 0xFFFFFF);
    }
  }

  static void ui(String in) throws Exception {
    NodeList nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new File(in))
        .getElementsByTagName("node");
    for (int i = 0; i < nodes.getLength(); i++) {
      Element n = (Element) nodes.item(i);
      String text = n.getAttribute("text").replace('\n', ' ');
      String desc = n.getAttribute("content-desc").replace('\n', ' ');
      String id = n.getAttribute("resource-id");
      boolean click = "true".equals(n.getAttribute("clickable")) || "true".equals(n.getAttribute("long-clickable"));
      if (text.isEmpty() && desc.isEmpty() && id.isEmpty() && !click) continue;
      String b = n.getAttribute("bounds"); // [x1,y1][x2,y2]
      String[] v = b.replaceAll("[\\[\\]]", ",").split(",+");
      int x1 = Integer.parseInt(v[1]), y1 = Integer.parseInt(v[2]);
      int x2 = Integer.parseInt(v[3]), y2 = Integer.parseInt(v[4]);
      if (x2 <= x1 || y2 <= y1) continue;
      String cls = n.getAttribute("class");
      cls = cls.substring(cls.lastIndexOf('.') + 1);
      StringBuilder sb = new StringBuilder();
      sb.append('(').append((x1 + x2) / 2).append(',').append((y1 + y2) / 2).append(") ")
          .append(x2 - x1).append('x').append(y2 - y1).append(' ').append(cls);
      if (click) sb.append(" [tap]");
      if ("true".equals(n.getAttribute("selected")) || "true".equals(n.getAttribute("checked"))) sb.append(" [on]");
      if (!text.isEmpty()) sb.append(" \"").append(trim(text)).append('"');
      if (!desc.isEmpty()) sb.append(" desc=\"").append(trim(desc)).append('"');
      if (!id.isEmpty()) sb.append(" id=").append(id.substring(id.indexOf('/') + 1));
      System.out.println(sb);
    }
  }

  static String trim(String s) {
    return s.length() > 60 ? s.substring(0, 57) + "..." : s;
  }
}

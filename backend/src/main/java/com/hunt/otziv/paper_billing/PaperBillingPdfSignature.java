package com.hunt.otziv.paper_billing;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

/** Adds only the supplier's supplied PNG; bank originals remain available for audit. */
@Service @RequiredArgsConstructor
public class PaperBillingPdfSignature {
    private final PaperBillingSettings settings;
    public record Pdf(byte[] original,byte[] delivered,String signatureSha256) {}

    public Pdf apply(byte[] original,boolean act) throws Exception {
        if (settings.getSignaturePath().isBlank()) return new Pdf(original,original,null);
        if (!PaperBillingModels.validInn(settings.getSignatureSupplierInn()))
            throw new IllegalStateException("Укажите ИНН исполнителя для изображения подписи");
        Path path=Path.of(settings.getSignaturePath());
        if (Files.size(path)>5*1024*1024) throw new IOException("PNG подписи слишком большой");
        byte[] source=Files.readAllBytes(path);
        if (source.length<8 || source[0]!=(byte)137 || source[1]!=80 || source[2]!=78 || source[3]!=71)
            throw new IOException("Подпись должна быть в PNG");
        BufferedImage image=ImageIO.read(new java.io.ByteArrayInputStream(source));
        if (image==null || (long)image.getWidth()*image.getHeight()>16_000_000L)
            throw new IOException("Не удалось прочитать PNG подписи");
        image=visibleImage(image);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
        try (PDDocument pdf=Loader.loadPDF(original)) {
            if (!pdf.getSignatureDictionaries().isEmpty()) throw new IOException("PDF уже содержит электронную подпись");
            var text=new PositionedText();
            String full=text.getText(pdf);
            String buyerLabel=act?"Заказчик:":"Плательщик:";
            int buyer=full.indexOf(buyerLabel);
            String supplier=buyer>=0?full.substring(0,buyer):"";
            if (!supplier.matches("(?s).*ИНН\\s+"+settings.getSignatureSupplierInn()+"(?:\\D|$).*"))
                throw new IOException("ИНН исполнителя PDF не совпал с владельцем подписи");
            Line anchor=text.anchor(act?"Исполнитель:":"Получатель:");
            var page=pdf.getPage(anchor.page-1);
            if (page.getRotation()!=0 || page.getCropBox().getLowerLeftX()!=0 || page.getCropBox().getLowerLeftY()!=0)
                throw new IOException("Неподдерживаемая геометрия PDF для изображения подписи");
            float pageWidth=page.getCropBox().getWidth(),pageHeight=page.getCropBox().getHeight();
            var underscores=anchor.positions.stream().filter(p -> p.getUnicode().contains("_") && p.getXDirAdj()<pageWidth/2).toList();
            if (underscores.isEmpty()) throw new IOException("Не найдено поле подписи исполнителя");
            float left=(float)underscores.stream().mapToDouble(TextPosition::getXDirAdj).min().orElseThrow();
            float right=(float)underscores.stream().mapToDouble(p -> p.getXDirAdj()+p.getWidthDirAdj()).max().orElseThrow();
            float height=Math.min(45,text.freeSpaceAbove(anchor)-10);
            if (height<16 || right-left<35) throw new IOException("Недостаточно места для подписи исполнителя");
            float width=Math.min(110,right-left);
            float scale=Math.min(width/image.getWidth(),height/image.getHeight());
            width=image.getWidth()*scale;height=image.getHeight()*scale;
            float bottom=pageHeight-anchor.y-3;
            if (bottom<0 || bottom+height>pageHeight) throw new IOException("Поле подписи выходит за границы PDF");
            try (var stream=new PDPageContentStream(pdf,page,PDPageContentStream.AppendMode.APPEND,true,true)) {
                stream.drawImage(LosslessFactory.createFromImage(pdf,image),left+(right-left-width)/2,bottom,width,height);
            }
            pdf.getDocumentInformation().setCustomMetadataValue("VisualSignatureSHA256",hash);
            var output=new ByteArrayOutputStream();pdf.save(output);
            return new Pdf(original,output.toByteArray(),hash);
        }
    }
    private static BufferedImage visibleImage(BufferedImage image) throws IOException {
        int left=image.getWidth(),top=image.getHeight(),right=-1,bottom=-1;
        for(int y=0;y<image.getHeight();y++) for(int x=0;x<image.getWidth();x++) {
            int pixel=image.getRGB(x,y);
            if ((pixel>>>24)>8 && (pixel&0xFFFFFF)!=0xFFFFFF) {
                left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);
            }
        }
        if(right<left) throw new IOException("PNG подписи пустой");
        return image.getSubimage(left,top,right-left+1,bottom-top+1);
    }
    private static final class Line {
        final int page;final float y;final List<TextPosition> positions=new ArrayList<>();
        Line(int page,float y) {this.page=page;this.y=y;}
        String text() {
            positions.sort(Comparator.comparingDouble(TextPosition::getXDirAdj));
            return positions.stream().map(TextPosition::getUnicode).collect(java.util.stream.Collectors.joining());
        }
    }
    private static final class PositionedText extends PDFTextStripper {
        final List<Line> lines=new ArrayList<>();
        PositionedText() throws IOException {setSortByPosition(true);}
        @Override protected void writeString(String text,List<TextPosition> positions) throws IOException {
            super.writeString(text,positions);
            for(var p:positions) {
                var line=lines.stream().filter(l -> l.page==getCurrentPageNo() && Math.abs(l.y-p.getYDirAdj())<2)
                        .findFirst().orElseGet(() -> {var l=new Line(getCurrentPageNo(),p.getYDirAdj());lines.add(l);return l;});
                line.positions.add(p);
            }
        }
        Line anchor(String label) throws IOException {
            return lines.stream().filter(l -> {String s=l.text();return s.contains(label) && s.contains("________");})
                    .max(Comparator.comparingInt((Line l)->l.page).thenComparingDouble(l->l.y))
                    .orElseThrow(() -> new IOException("Не найдено поле подписи исполнителя; PDF не изменён"));
        }
        float freeSpaceAbove(Line anchor) {
            float previous=(float)lines.stream().filter(l -> l.page==anchor.page && l.y<anchor.y-2)
                    .mapToDouble(l -> l.y).max().orElse(0);
            return anchor.y-previous;
        }
    }
}

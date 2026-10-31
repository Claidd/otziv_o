package com.hunt.otziv.paper_billing;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PaperBillingPdfSignatureTest {
    @TempDir Path temp;
    private PaperBillingSettings settings() throws Exception {
        var image=new BufferedImage(120,60,BufferedImage.TYPE_INT_ARGB);
        var g=image.createGraphics();g.setColor(Color.BLUE);g.drawLine(8,50,110,8);g.dispose();
        Path file=temp.resolve("signature.png");ImageIO.write(image,"png",file.toFile());
        var settings=new PaperBillingSettings();settings.setSignaturePath(file.toString());settings.setSignatureSupplierInn("7707083893");
        return settings;
    }
    private byte[] bankPdf(boolean act,boolean field) throws Exception {
        try(var pdf=new PDDocument()) {
            var page=new PDPage(PDRectangle.A4);pdf.addPage(page);
            try(var fontSource=getClass().getResourceAsStream("/static/font/borders.ttf");
                    var stream=new PDPageContentStream(pdf,page)) {
                var font=PDType0Font.load(pdf,fontSource,true);
                line(stream,font,40,790,"Исполнитель: ООО Тест, ИНН 7707083893");
                line(stream,font,40,760,(act?"Заказчик:":"Плательщик:")+" ООО Покупатель");
                line(stream,font,40,500,"Стоимость работ 1000 рублей");
                if(field) {
                    line(stream,font,40,350,(act?"Исполнитель:":"Получатель:")+" ____________________");
                    if(act) line(stream,font,350,350,"Заказчик: ______________");
                }
            }
            var output=new ByteArrayOutputStream();pdf.save(output);return output.toByteArray();
        }
    }
    private void line(PDPageContentStream stream,PDType0Font font,float x,float y,String text) throws IOException {
        stream.beginText();stream.setFont(font,10);stream.newLineAtOffset(x,y);stream.showText(text);stream.endText();
    }
    @Test void disabledSignatureKeepsTheBankPdfUnchanged() throws Exception {
        byte[] original=new byte[]{1,2,3};
        var result=new PaperBillingPdfSignature(new PaperBillingSettings()).apply(original,false);
        assertSame(original,result.delivered());assertSame(original,result.original());assertNull(result.signatureSha256());
    }
    @Test void transparentSignatureChangesOnlyTheSupplierFieldAndPreservesOriginal() throws Exception {
        var signer=new PaperBillingPdfSignature(settings());
        for(boolean act:new boolean[]{false,true}) {
            byte[] original=bankPdf(act,true);
            var result=signer.apply(original,act);
            assertArrayEquals(original,result.original());assertNotEquals(original.length,result.delivered().length);
            assertEquals(64,result.signatureSha256().length());
            try(var before=Loader.loadPDF(original);var after=Loader.loadPDF(result.delivered())) {
                assertEquals(1,after.getNumberOfPages());assertTrue(after.getSignatureDictionaries().isEmpty());
                var a=new PDFRenderer(before).renderImage(0);var b=new PDFRenderer(after).renderImage(0);
                int changed=0,minX=a.getWidth(),minY=a.getHeight(),maxX=0,maxY=0;
                for(int y=0;y<a.getHeight();y++) for(int x=0;x<a.getWidth();x++) if(a.getRGB(x,y)!=b.getRGB(x,y)) {
                    changed++;minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
                }
                assertTrue(minX>70 && maxX<280,"Supplier signature must stay on the left: "+minX+".."+maxX);
                assertTrue(minY>430 && maxY<500,"Signature must stay above its field: "+minY+".."+maxY);
                assertTrue(changed>20);assertTrue(changed<3000,"Transparent PNG must not paint a solid rectangle");
            }
        }
    }
    @Test void wrongSupplierOrMissingFieldCannotProduceASignedPdf() throws Exception {
        var cfg=settings();cfg.setSignatureSupplierInn("380124742639");
        byte[] original=bankPdf(true,true);
        assertThrows(IOException.class,()->new PaperBillingPdfSignature(cfg).apply(original,true));
        cfg.setSignatureSupplierInn("7707083893");
        byte[] noField=bankPdf(true,false);
        assertThrows(IOException.class,()->new PaperBillingPdfSignature(cfg).apply(noField,true));
    }
}

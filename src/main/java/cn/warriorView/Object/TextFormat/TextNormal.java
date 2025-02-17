package cn.warriorView.Object.TextFormat;

public class TextNormal implements ITextFormat {

    private String text;

    @Override
    public String format(double value) {
        return String.format(text, value);
    }
}

package cn.warriorView.Object.Text;

public class TextNormal {

    private String text;

    public String format(double value) {
        return String.format(text, value);
    }
}

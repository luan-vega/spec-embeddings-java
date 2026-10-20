package br.misto;

public class Misto {
    private static final String SQL = """
        select * from t where x = '{' and y = "}"
        """;

    public String descrição(String ação) {
        return ação + SQL;
    }

    public void emoji() {
        String s = "😀 ok"; int ação2 = 1;
    }
}

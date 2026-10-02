package mx.octomind.progol;

public enum DrawType {
    MS(25, 9, "Mitad de Semana", "octomind_progol.db", "UAByAG8AZwBvAGwALQBNAGUAZABpAGEALQBTAA=="),
    WEEKEND(10, 14, "Fin de Semana", "octomind_weekend.db", "UAByAG8AZwBvAGwA"),
    REVANCHA(10, 7, "Revancha", "octomind_revancha.db", "UAByAG8AZwBvAGwALQBSAGUAdgBhAG4AYwBoAGEA");

    public final int product;
    public final int slots;
    public final String label;
    public final String databaseName;
    public final String url;

    DrawType(int product, int slots, String label, String databaseName, String query) {
        this.product = product;
        this.slots = slots;
        this.label = label;
        this.databaseName = databaseName;
        this.url = "https://www.loterianacional.gob.mx/Home/Historicos?ARHP=" + query;
    }

    public String modelVersion() { return this == MS ? PredictionEngine.MODEL_VERSION : name().toLowerCase(java.util.Locale.ROOT) + "-baseline-1"; }

    public static DrawType identify(int product, int slots) {
        for (DrawType type : values()) {
            if (type.product == product && type.slots == slots) return type;
        }
        throw new IllegalArgumentException("Producto o cantidad de partidos desconocidos");
    }
}

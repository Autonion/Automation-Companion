/**
 * Run against the actual release DEX with Android's dalvikvm -Xverify:all.
 * This loads classes without creating an Activity, starting servers, or accessing app data.
 * Pass mapped class names from the mapping belonging to the tested artifact.
 */
public final class ReleaseClassVerifier {
    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Supply release class names");
        for (String name : args) {
            Class<?> type = Class.forName(name, true, ReleaseClassVerifier.class.getClassLoader());
            type.getDeclaredConstructor();
            type.getDeclaredMethods();
            System.out.println("VERIFIED " + name + " extends " + type.getSuperclass());
        }
    }
}

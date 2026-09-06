# RoomExpenseSplitter — Firebase Shared Version

This version uses Firebase Authentication (Anonymous) and Realtime Database.

- Expenses are stored under `household/default/expenses`.
- Room members are stored under `household/default/members`.
- All app installations using this Firebase project see the same data in real time.
- Car visibility remains a local UI preference.

## Firebase setup
1. Add the included `app/google-services.json` to the Android app module.
2. Enable Authentication → Sign-in method → Anonymous.
3. Create/enable Realtime Database.
4. Set Realtime Database Rules to the contents of `FIREBASE_RULES.txt`.
5. Build and install the app on each phone.

The app package is `com.example.roomexpensesplitter`.

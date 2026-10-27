import SwiftUI

/// StateObject owns this box lazily: rebuilding a screen value does not construct
/// and discard another observable model. The model itself uses Observation.
@MainActor
final class ViewModelOwner<Model>: ObservableObject {
    let model: Model
    init(_ create: () -> Model) { model = create() }
}
